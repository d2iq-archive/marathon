package mesosphere.mesos

import mesosphere.marathon.state.PathId
import scala.collection.JavaConverters._
import scala.collection.mutable

import java.io.ByteArrayOutputStream

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.ByteString
import org.apache.log4j.Logger
import org.apache.mesos.Protos.Environment._
import org.apache.mesos.Protos._

import mesosphere.marathon._
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.mesos.protos.{ RangesResource, Resource, ScalarResource }

import scala.util.{ Random, Failure, Success, Try }

class TaskBuilder(app: AppDefinition,
                  newTaskId: PathId => TaskID,
                  taskTracker: TaskTracker,
                  config: MarathonConf,
                  mapper: ObjectMapper = new ObjectMapper()) {

  import mesosphere.mesos.protos.Implicits._

  val log = Logger.getLogger(getClass.getName)

  def buildIfMatches(offer: Offer): Option[(TaskInfo, Seq[Long])] = {
    var cpuRole = ""
    var memRole = ""
    var diskRole = ""

    offerMatches(offer) match {
      case Some((cpu, mem, disk)) =>
        cpuRole = cpu
        memRole = mem
        diskRole = disk
      case _ =>
        log.info(s"No matching offer for ${app.id} (need ${app.cpus} CPUs, ${app.mem} mem, ${app.disk} disk, ${app.ports.size} ports) : " + offer)
        return None
    }

    val executor: Executor = if (app.executor == "") {
      Main.conf.executor
    }
    else {
      Executor.dispatch(app.executor)
    }

    TaskBuilder.getPorts(offer, app.ports.size).map { portsResource =>
      val ports = portsResource.ranges.flatMap(_.asScala())

      val taskId = newTaskId(app.id)
      val builder = TaskInfo.newBuilder
        .setName(taskId.getValue)
        .setTaskId(taskId)
        .setSlaveId(offer.getSlaveId)
        .addResources(ScalarResource(Resource.CPUS, app.cpus, cpuRole))
        .addResources(ScalarResource(Resource.MEM, app.mem, memRole))

      if (portsResource.ranges.nonEmpty) {
        builder.addResources(portsResource)
      }

      executor match {
        case CommandExecutor() =>
          builder.setCommand(TaskBuilder.commandInfo(app, ports))

        case PathExecutor(path) =>
          val executorId = f"marathon-${taskId.getValue}" // Fresh executor
          val escaped = "'" + path + "'" // TODO: Really escape this.
          val shell = f"chmod ug+rx $escaped && exec $escaped ${app.cmd}"
          val command =
            TaskBuilder.commandInfo(app, ports).toBuilder.setValue(shell)

          val info = ExecutorInfo.newBuilder()
            .setExecutorId(ExecutorID.newBuilder().setValue(executorId))
            .setCommand(command)

          builder.setExecutor(info)
          val binary = new ByteArrayOutputStream()
          mapper.writeValue(binary, app)
          builder.setData(ByteString.copyFrom(binary.toByteArray))
      }

      if (config.executorHealthChecks()) {
        import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol

        // Mesos supports at most one health check, and only COMMAND checks
        // are currently implemented.
        val mesosHealthCheck: Option[org.apache.mesos.Protos.HealthCheck] =
          app.healthChecks.collectFirst {
            case healthCheck if healthCheck.protocol == Protocol.COMMAND =>
              Try(healthCheck.toMesos(ports.map(_.toInt))) match {
                case Success(mhc) => Some(mhc)
                case Failure(cause) =>
                  log.warn(
                    s"An error occurred with health check [$healthCheck]\n" +
                      s"Error: [${cause.getMessage}]")
                  None
              }
          }.flatten

        mesosHealthCheck foreach builder.setHealthCheck

        if (mesosHealthCheck.size < app.healthChecks.size) {
          val numUnusedChecks = app.healthChecks.size - mesosHealthCheck.size
          log.warn(
            "Mesos supports one command health check per task.\n" +
              s"Task [$taskId] will run without " +
              s"$numUnusedChecks of its defined health checks."
          )
        }
      }

      builder.build -> ports
    }
  }

  private def offerMatches(offer: Offer): Option[(String, String, String)] = {
    var cpuRole = ""
    var memRole = ""
    var diskRole = ""

    for (resource <- offer.getResourcesList.asScala) {
      if (cpuRole.isEmpty &&
        resource.getName == Resource.CPUS &&
        resource.getScalar.getValue >= app.cpus) {
        cpuRole = resource.getRole
      }
      if (memRole.isEmpty &&
        resource.getName == Resource.MEM &&
        resource.getScalar.getValue >= app.mem) {
        memRole = resource.getRole
      }
      if (diskRole.isEmpty &&
        resource.getName == Resource.DISK &&
        resource.getScalar.getValue >= app.disk) {
        diskRole = resource.getRole
      }
    }

    if (cpuRole.isEmpty || memRole.isEmpty || diskRole.isEmpty) {
      return None
    }

    if (app.constraints.nonEmpty) {
      val runningTasks = taskTracker.get(app.id)
      val constraintsMet = app.constraints.forall(
        Constraints.meetsConstraint(runningTasks, offer, _)
      )
      if (!constraintsMet) {
        log.warn("Did not meet a constraint in an offer.")
        return None
      }
      log.info("Met all constraints.")
    }
    Some((cpuRole, memRole, diskRole))
  }
}

object TaskBuilder {

  def commandInfo(app: AppDefinition, ports: Seq[Long]) = {
    val envMap = app.env ++ portsEnv(ports)

    val builder = CommandInfo.newBuilder()
      .setValue(app.cmd)
      .setEnvironment(environment(envMap))

    for (c <- app.container) {
      val container = CommandInfo.ContainerInfo.newBuilder()
        .setImage(c.image)
        .addAllOptions(c.options.asJava)
      builder.setContainer(container)
    }

    if (app.uris != null) {
      val uriProtos = app.uris.map(uri => {
        CommandInfo.URI.newBuilder()
          .setValue(uri)
          .build()
      })
      builder.addAllUris(uriProtos.asJava)
    }

    app.user.foreach(builder.setUser)

    builder.build
  }

  def environment(vars: Map[String, String]) = {
    val builder = Environment.newBuilder()

    for ((key, value) <- vars) {
      val variable = Variable.newBuilder().setName(key).setValue(value)
      builder.addVariables(variable)
    }

    builder.build()
  }

  def getPorts(offer: Offer, numPorts: Int): Option[RangesResource] = {
    offer.getResourcesList.asScala
      .find(_.getName == Resource.PORTS)
      .flatMap(getPorts(_, numPorts))
  }

  def getPorts(resource: org.apache.mesos.Protos.Resource,
               numPorts: Int): Option[RangesResource] = {
    if (numPorts == 0) {
      return Some(RangesResource(Resource.PORTS, Nil))
    }

    val ranges = Random.shuffle(resource.getRanges.getRangeList.asScala)
    for (range <- ranges) {
      // TODO use multiple ranges if one is not enough
      if (range.getEnd - range.getBegin + 1 >= numPorts) {
        val maxOffset = (range.getEnd - range.getBegin - numPorts + 2).toInt
        val firstPort = range.getBegin.toInt + Random.nextInt(maxOffset)
        val rangeProto = protos.Range(firstPort, firstPort + numPorts - 1)
        return Some(
          RangesResource(Resource.PORTS, Seq(rangeProto), resource.getRole)
        )
      }
    }
    None
  }

  def portsEnv(ports: Seq[Long]): scala.collection.Map[String, String] = {
    if (ports.isEmpty) {
      return Map.empty
    }

    val env = mutable.HashMap.empty[String, String]

    ports.zipWithIndex.foreach(p => {
      env += (s"PORT${p._2}" -> p._1.toString)
    })

    env += ("PORT" -> ports.head.toString)
    env += ("PORTS" -> ports.mkString(","))
    env
  }
}
