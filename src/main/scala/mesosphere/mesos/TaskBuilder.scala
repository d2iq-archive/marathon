package mesosphere.mesos

import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Environment.Variable
import scala.collection._
import scala.collection.JavaConverters._
import mesosphere.marathon.api.v1.AppDefinition
import org.apache.mesos.Protos.Value.Ranges
import mesosphere.marathon.tasks.TaskTracker
import java.util.logging.Logger
import mesosphere.marathon.{PathExecutor, CommandExecutor, Executor, Main}
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import com.google.protobuf.ByteString
import org.apache.mesos.Protos


/**
 * @author Tobi Knaup
 */

class TaskBuilder (app: AppDefinition,
                   newTaskId: String => TaskID,
                   taskTracker: TaskTracker,
                   mapper: ObjectMapper = new ObjectMapper()) {

  val log = Logger.getLogger(getClass.getName)

  def buildIfMatches(offer: Offer): Option[(TaskInfo, Seq[Int])] = {
    if (!offerMatches(offer)) {
      return None
    }

    val executor: Executor = if (app.executor == "") {
      Main.getConfiguration.executor
    } else {
      Executor.dispatch(app.executor)
    }

    TaskBuilder.getPorts(offer, app.ports.size).map(f = portRanges => {
      val ports = portRanges.map(r => {
        Seq.range(r._1, r._2 + 1)
      }).flatten

      val taskId = newTaskId(app.id)
      val builder = TaskInfo.newBuilder
        .setName(taskId.getValue)
        .setTaskId(taskId)
        .setSlaveId(offer.getSlaveId)
        .addResources(TaskBuilder.
          scalarResource(TaskBuilder.cpusResourceName, app.cpus))
        .addResources(TaskBuilder.
          scalarResource(TaskBuilder.memResourceName, app.mem))

      if (portRanges.nonEmpty) {
        builder.addResources(portsResource(portRanges))
      }

      executor match {
        case CommandExecutor() =>
          builder.setCommand(TaskBuilder.commandInfo(app, ports))

        case PathExecutor(path) => {
          val executorId = f"marathon-${taskId.getValue}" // Fresh executor
          val escaped = "'" + path + "'" // TODO: Really escape this.
          val cmd = f"chmod ug+rx $escaped && exec $escaped ${app.cmd}"
          val binary = new ByteArrayOutputStream()
          mapper.writeValue(binary, app)
          val info = ExecutorInfo.newBuilder()
            .setExecutorId(ExecutorID.newBuilder().setValue(executorId))
            .setCommand(CommandInfo.newBuilder().setValue(cmd))
          builder.setExecutor(info)
          builder.setData(ByteString.copyFrom(binary.toByteArray))
        }
      }

      builder.build -> ports
    })
  }

  private def portsResource(ranges: Seq[(Int, Int)]): Resource = {
    val rangeProtos = ranges.map(r => {
      Value.Range.newBuilder
        .setBegin(r._1)
        .setEnd(r._2)
        .build
    })

    val rangesProto = Ranges.newBuilder
      .addAllRange(rangeProtos.asJava)
      .build
    Resource.newBuilder
      .setName(TaskBuilder.portsResourceName)
      .setType(Value.Type.RANGES)
      .setRanges(rangesProto)
      .build
  }

  private def offerMatches(offer: Offer): Boolean = {
    for (resource <- offer.getResourcesList.asScala) {
      if (resource.getName.eq(TaskBuilder.cpusResourceName) && resource.getScalar.getValue < app.cpus) {
        return false
      }
      if (resource.getName.eq(TaskBuilder.memResourceName) && resource.getScalar.getValue < app.mem) {
        return false
      }
      // TODO handle other resources
    }

    if (app.constraints.nonEmpty) {
      val currentlyRunningTasks = taskTracker.get(app.id)
      if (app.constraints.filterNot(x =>
          Constraints
            .meetsConstraint(
              currentlyRunningTasks.toSet,
              offer.getAttributesList.asScala.toSet,
              x._1,
              x._2,
              x._3))
        .nonEmpty) {
        log.warning("Did not meet a constraint in an offer." )
        return false
      }
      log.info("Met all constraints.")
    }
    true
  }
}

object TaskBuilder {

  final val cpusResourceName = "cpus"
  final val memResourceName = "mem"
  final val portsResourceName = "ports"

  def scalarResource(name: String, value: Double) = {
    Resource.newBuilder
      .setName(name)
      .setType(Value.Type.SCALAR)
      .setScalar(Value.Scalar.newBuilder.setValue(value))
      .build
  }

  def commandInfo(app: AppDefinition, ports: Seq[Int]) = {
    val envMap = app.env ++ portsEnv(ports)

    val builder = CommandInfo.newBuilder()
      .setValue(app.cmd)
      .setEnvironment(environment(envMap))

    if (app.uris != null) {
      val uriProtos = app.uris.map(uri => {
        CommandInfo.URI.newBuilder()
          .setValue(uri)
          .build()
      })
      builder.addAllUris(uriProtos.asJava)
    }

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

  def getPorts(offer: Offer, numPorts: Int): Option[Seq[(Int, Int)]] = {
    offer.getResourcesList.asScala
      .find(_.getName == portsResourceName)
      .flatMap(getPorts(_, numPorts))
  }

  def getPorts(resource: Resource, numPorts: Int): Option[Seq[(Int, Int)]] = {
    if (numPorts == 0) {
      return Some(Seq())
    }

    val ranges = util.Random.shuffle(resource.getRanges.getRangeList.asScala)
    for (range <- ranges) {
      // TODO use multiple ranges if one is not enough
      if (range.getEnd - range.getBegin + 1 >= numPorts) {
        val maxOffset = (range.getEnd - range.getBegin - numPorts + 2).toInt
        val firstPort = range.getBegin.toInt + util.Random.nextInt(maxOffset)
        return Some(Seq((firstPort, firstPort + numPorts - 1)))
      }
    }
    None
  }

  def portsEnv(ports: Seq[Int]): Map[String, String] = {
    if (ports.isEmpty) {
      return Map.empty
    }

    val env = mutable.HashMap.empty[String, String]

    ports.zipWithIndex.foreach(p => {
      env += (s"PORT${p._2}" -> p._1.toString)
    })

    env += ("PORT" -> ports.head.toString)
    env
  }
}
