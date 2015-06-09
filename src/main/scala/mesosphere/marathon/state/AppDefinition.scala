package mesosphere.marathon.state

import java.lang.{ Double => JDouble, Integer => JInt }

import com.fasterxml.jackson.annotation.{ JsonIgnore, JsonIgnoreProperties, JsonProperty }
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.v2.json.EnrichedTask
import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.validation.{ PortIndices, ValidAppDefinition }
import mesosphere.marathon.health.{ HealthCheck, HealthCounts }
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.mesos.TaskBuilder
import mesosphere.mesos.protos.{ Resource, ScalarResource, RangesResource, SetResource }
import org.apache.mesos.Protos.Value
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
import org.apache.mesos.{ Protos => mesos }
import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import org.apache.log4j.Logger // TODOC remove this

@PortIndices
@JsonIgnoreProperties(ignoreUnknown = true)
@ValidAppDefinition
case class AppDefinition(

  id: PathId = AppDefinition.DefaultId,

  cmd: Option[String] = AppDefinition.DefaultCmd,

  args: Option[Seq[String]] = AppDefinition.DefaultArgs,

  user: Option[String] = AppDefinition.DefaultUser,

  env: Map[String, String] = AppDefinition.DefaultEnv,

  @FieldMin(0) instances: JInt = AppDefinition.DefaultInstances,

  cpus: JDouble = AppDefinition.DefaultCpus,

  mem: JDouble = AppDefinition.DefaultMem,

  disk: JDouble = AppDefinition.DefaultDisk,

  //customResources: Map[String, JDouble] = AppDefinition.DefaultCustomResources,
  customResources: Map[String, CustomResource] = AppDefinition.DefaultCustomResources,

  @FieldPattern(regexp = "^(//cmd)|(/?[^/]+(/[^/]+)*)|$") executor: String = AppDefinition.DefaultExecutor,

  constraints: Set[Constraint] = AppDefinition.DefaultConstraints,

  uris: Seq[String] = AppDefinition.DefaultUris,

  storeUrls: Seq[String] = AppDefinition.DefaultStoreUrls,

  @FieldPortsArray ports: Seq[JInt] = AppDefinition.DefaultPorts,

  requirePorts: Boolean = AppDefinition.DefaultRequirePorts,

  @FieldJsonProperty("backoffSeconds") backoff: FiniteDuration = AppDefinition.DefaultBackoff,

  backoffFactor: JDouble = AppDefinition.DefaultBackoffFactor,

  @FieldJsonProperty("maxLaunchDelaySeconds") maxLaunchDelay: FiniteDuration = AppDefinition.DefaultMaxLaunchDelay,

  container: Option[Container] = AppDefinition.DefaultContainer,

  healthChecks: Set[HealthCheck] = AppDefinition.DefaultHealthChecks,

  dependencies: Set[PathId] = AppDefinition.DefaultDependencies,

  upgradeStrategy: UpgradeStrategy = AppDefinition.DefaultUpgradeStrategy,

  labels: Map[String, String] = AppDefinition.DefaultLabels,

  acceptedResourceRoles: Option[Set[String]] = None,

  version: Timestamp = Timestamp.now()) extends MarathonState[Protos.ServiceDefinition, AppDefinition]
    with Timestamped {

  import mesosphere.mesos.protos.Implicits._

  val log = Logger.getLogger(getClass.getName) // TODOC remove this

  assert(
    portIndicesAreValid(),
    "Health check port indices must address an element of the ports array or container port mappings."
  )

  /**
    * Returns true if all health check port index values are in the range
    * of ths app's ports array, or if defined, the array of container
    * port mappings.
    */
  def portIndicesAreValid(): Boolean = {
    val validPortIndices = 0 until hostPorts.size
    healthChecks.forall { hc =>
      hc.protocol == Protocol.COMMAND || (validPortIndices contains hc.portIndex)
    }
  }

  def toProto: Protos.ServiceDefinition = {
    val commandInfo = TaskBuilder.commandInfo(this, None, None, Seq.empty)
    val cpusResource = ScalarResource(Resource.CPUS, cpus)
    val memResource = ScalarResource(Resource.MEM, mem)
    val diskResource = ScalarResource(Resource.DISK, disk)
    /*
    val customResourcesList = customResources.flatMap {
      case (name, resource) =>
        value.getType match {
          case Value.Type.SCALAR =>
            List(ScalarResource(name, resource.scalar.get.value))
          case Value.Type.RANGES =>
            log.info("TODOC print ranges resources")
            resource.ranges.get.foreach { r => println(r) }
            List()
          // foreach rangeresource add it to list
          //resource.ranges.get.foreach { r => println(r) }
          //resource.ranges.get.map { r =>
          //  RangesResource(resource.name, r: Seq[mesosphere.mesos.protos.Range])
          //}
          //RangesResource(resource.name, resource.ranges.get: mesosphere.mesos.protos.Range)
          case Value.Type.SET =>
            List(SetResource(name, resource.set.get.value))
          case default =>
            log.info("TODOC invalid resource type")
            List()
        }
    }*/
    val appLabels = labels.map {
      case (key, value) =>
        mesos.Parameter.newBuilder
          .setKey(key)
          .setValue(value)
          .build
    }

    val builder = Protos.ServiceDefinition.newBuilder
      .setId(id.toString)
      .setCmd(commandInfo)
      .setInstances(instances)
      .addAllPorts(ports.asJava)
      .setRequirePorts(requirePorts)
      .setBackoff(backoff.toMillis)
      .setBackoffFactor(backoffFactor)
      .setMaxLaunchDelay(maxLaunchDelay.toMillis)
      .setExecutor(executor)
      .addAllConstraints(constraints.asJava)
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addAllHealthChecks(healthChecks.map(_.toProto).asJava)
      .setVersion(version.toString)
      .setUpgradeStrategy(upgradeStrategy.toProto)
      .addAllDependencies(dependencies.map(_.toString).asJava)
      .addAllStoreUrls(storeUrls.asJava)
      .addAllLabels(appLabels.asJava)
      .addAllCustomResources(customResources.map(_.toProto).asJava) // TODOC

    //customResourcesList.foreach(builder.addResources(_))

    container.foreach { c => builder.setContainer(c.toProto()) }

    acceptedResourceRoles.foreach { acceptedResourceRoles =>
      val roles = Protos.ResourceRoles.newBuilder()
      acceptedResourceRoles.seq.foreach(roles.addRole)
      builder.setAcceptedResourceRoles(roles)
    }

    builder.build
  }

  def mergeFromProto(proto: Protos.ServiceDefinition): AppDefinition = {
    val envMap: Map[String, String] =
      proto.getCmd.getEnvironment.getVariablesList.asScala.map {
        v => v.getName -> v.getValue
      }.toMap

    val resourcesMap: Map[String, JDouble] =
      proto.getResourcesList.asScala.map {
        r => r.getName -> (r.getScalar.getValue: JDouble)
      }.toMap

    log.info("TODOC resourcesMAP")
    log.info(proto.getResourcesList.asScala)

    //val customResourcesMap: Map[String, JDouble] = resourcesMap
    val standardResources = Set(Resource.CPUS, Resource.MEM, Resource.DISK, Resource.PORTS)
    val customResourcesMap: Map[String, CustomResource] = proto.getCustomResourcesList.asScala
      .map(r => CustomResource.create(r).get).toList
    //  TODOC I wanted to try printing out .getScalar, .getRanges, .getItem etc to see what happens
    log.info("TODOC get")
    proto.getResourcesList.asScala.foreach(r => println(r.getSet))

    val commandOption =
      if (proto.getCmd.hasValue && proto.getCmd.getValue.nonEmpty)
        Some(proto.getCmd.getValue)
      else None

    val argsOption =
      if (commandOption.isEmpty && proto.getCmd.getArgumentsCount != 0)
        Some(proto.getCmd.getArgumentsList.asScala.to[Seq])
      else None

    val containerOption =
      if (proto.hasContainer)
        Some(Container(proto.getContainer))
      else if (proto.getCmd.hasContainer)
        Some(Container(proto.getCmd.getContainer))
      else if (proto.hasOBSOLETEContainer)
        Some(Container(proto.getOBSOLETEContainer))
      else None

    val acceptedResourceRoles: Option[Set[String]] =
      if (proto.hasAcceptedResourceRoles)
        Some(proto.getAcceptedResourceRoles.getRoleList.asScala.toSet)
      else
        None

    AppDefinition(
      id = proto.getId.toPath,
      user = if (proto.getCmd.hasUser) Some(proto.getCmd.getUser) else None,
      cmd = commandOption,
      args = argsOption,
      executor = proto.getExecutor,
      instances = proto.getInstances,
      ports = proto.getPortsList.asScala.to[Seq],
      requirePorts = proto.getRequirePorts,
      backoff = proto.getBackoff.milliseconds,
      backoffFactor = proto.getBackoffFactor,
      maxLaunchDelay = proto.getMaxLaunchDelay.milliseconds,
      constraints = proto.getConstraintsList.asScala.toSet,
      acceptedResourceRoles = acceptedResourceRoles,
      cpus = resourcesMap.getOrElse(Resource.CPUS, this.cpus),
      mem = resourcesMap.getOrElse(Resource.MEM, this.mem),
      disk = resourcesMap.getOrElse(Resource.DISK, this.disk),
      customResources = customResourcesMap,
      env = envMap,
      uris = proto.getCmd.getUrisList.asScala.map(_.getValue).to[Seq],
      storeUrls = proto.getStoreUrlsList.asScala.to[Seq],
      container = containerOption,
      healthChecks = proto.getHealthChecksList.asScala.map(new HealthCheck().mergeFromProto).toSet,
      labels = proto.getLabelsList.asScala.map { p => p.getKey -> p.getValue }.toMap,
      version = Timestamp(proto.getVersion),
      upgradeStrategy =
        if (proto.hasUpgradeStrategy) UpgradeStrategy.fromProto(proto.getUpgradeStrategy)
        else UpgradeStrategy.empty,
      dependencies = proto.getDependenciesList.asScala.map(PathId.apply).toSet
    )
  }

  @JsonIgnore
  def portMappings: Option[Seq[PortMapping]] =
    for {
      c <- container
      d <- c.docker
      n <- d.network if n == Network.BRIDGE
      pms <- d.portMappings
    } yield pms

  @JsonIgnore
  def containerHostPorts: Option[Seq[Int]] =
    for (pms <- portMappings) yield pms.map(_.hostPort.toInt)

  @JsonIgnore
  def containerServicePorts: Option[Seq[Int]] =
    for (pms <- portMappings) yield pms.map(_.servicePort.toInt)

  @JsonIgnore
  def hostPorts: Seq[Int] =
    containerHostPorts.getOrElse(ports.map(_.toInt))

  @JsonIgnore
  def servicePorts: Seq[Int] =
    containerServicePorts.getOrElse(ports.map(_.toInt))

  @JsonIgnore
  def hasDynamicPort: Boolean = servicePorts.contains(0)

  def mergeFromProto(bytes: Array[Byte]): AppDefinition = {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }

  def withTaskCountsAndDeployments(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan]): AppDefinition.WithTaskCountsAndDeployments = {
    new AppDefinition.WithTaskCountsAndDeployments(appTasks, healthCounts, runningDeployments, this)
  }

  def withTasksAndDeployments(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan]): AppDefinition.WithTasksAndDeployments =
    new AppDefinition.WithTasksAndDeployments(appTasks, healthCounts, runningDeployments, this)

  def withTasksAndDeploymentsAndFailures(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    taskFailure: Option[TaskFailure]): AppDefinition.WithTasksAndDeploymentsAndTaskFailures =
    new AppDefinition.WithTasksAndDeploymentsAndTaskFailures(
      appTasks, healthCounts,
      runningDeployments, taskFailure, this
    )

  def withNormalizedVersion: AppDefinition = copy(version = Timestamp(0))

  def isOnlyScaleChange(to: AppDefinition): Boolean =
    !isUpgrade(to) && (instances != to.instances)

  def isUpgrade(to: AppDefinition): Boolean =
    this != to.copy(instances = instances, version = version)
}

object AppDefinition {

  val RandomPortValue: Int = 0

  // App defaults
  val DefaultId: PathId = PathId.empty

  val DefaultCmd: Option[String] = None

  val DefaultArgs: Option[Seq[String]] = None

  val DefaultUser: Option[String] = None

  val DefaultEnv: Map[String, String] = Map.empty

  val DefaultInstances: Int = 1

  val DefaultCpus: Double = 1.0

  val DefaultMem: Double = 128.0

  val DefaultDisk: Double = 0.0

  //var DefaultCustomResources: Map[String, JDouble] = Map.empty
  var DefaultCustomResources: Seq[CustomResource] = Seq.empty

  val DefaultExecutor: String = ""

  val DefaultConstraints: Set[Constraint] = Set.empty

  val DefaultUris: Seq[String] = Seq.empty

  val DefaultStoreUrls: Seq[String] = Seq.empty

  val DefaultPorts: Seq[JInt] = Seq(RandomPortValue)

  val DefaultRequirePorts: Boolean = false

  val DefaultBackoff: FiniteDuration = 1.second

  val DefaultBackoffFactor = 1.15

  val DefaultMaxLaunchDelay: FiniteDuration = 1.hour

  val DefaultContainer: Option[Container] = None

  val DefaultHealthChecks: Set[HealthCheck] = Set.empty

  val DefaultDependencies: Set[PathId] = Set.empty

  val DefaultUpgradeStrategy: UpgradeStrategy = UpgradeStrategy.empty

  val DefaultLabels: Map[String, String] = Map.empty

  /**
    * This default is only used in tests
    */
  val DefaultAcceptedResourceRoles: Set[String] = Set.empty

  def fromProto(proto: Protos.ServiceDefinition): AppDefinition =
    AppDefinition().mergeFromProto(proto)

  protected[marathon] class WithTaskCountsAndDeployments(
    appTasks: Seq[EnrichedTask],
    healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    private val app: AppDefinition)
      extends AppDefinition(
        app.id, app.cmd, app.args, app.user, app.env, app.instances, app.cpus,
        app.mem, app.disk, app.customResources, app.executor, app.constraints, app.uris,
        app.storeUrls, app.ports, app.requirePorts, app.backoff,
        app.backoffFactor, app.maxLaunchDelay, app.container,
        app.healthChecks, app.dependencies, app.upgradeStrategy,
        app.labels, app.acceptedResourceRoles, app.version) {

    /**
      * Snapshot of the number of staged (but not running) tasks
      * for this app
      */
    @JsonProperty
    val tasksStaged: Int = appTasks.count { eTask =>
      eTask.task.getStagedAt != 0 && eTask.task.getStartedAt == 0
    }

    /**
      * Snapshot of the number of running tasks for this app
      */
    @JsonProperty
    val tasksRunning: Int = appTasks.count { eTask =>
      eTask.task.hasStatus &&
        eTask.task.getStatus.getState == mesos.TaskState.TASK_RUNNING
    }

    /**
      * Snapshot of the number of healthy tasks for this app
      */
    @JsonProperty
    val tasksHealthy: Int = healthCounts.healthy

    /**
      * Snapshot of the number of unhealthy tasks for this app
      */
    @JsonProperty
    val tasksUnhealthy: Int = healthCounts.unhealthy

    /**
      * Snapshot of the running deployments that affect this app
      */
    @JsonProperty
    def deployments: Seq[Identifiable] = {
      runningDeployments.collect {
        case plan if plan.affectedApplicationIds contains app.id => Identifiable(plan.id)
      }
    }
  }

  protected[marathon] class WithTasksAndDeployments(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    private val app: AppDefinition)
      extends WithTaskCountsAndDeployments(appTasks, healthCounts, runningDeployments, app) {

    @JsonProperty
    def tasks: Seq[EnrichedTask] = appTasks
  }

  protected[marathon] class WithTasksAndDeploymentsAndTaskFailures(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    taskFailure: Option[TaskFailure],
    private val app: AppDefinition)
      extends WithTasksAndDeployments(appTasks, healthCounts, runningDeployments, app) {

    @JsonProperty
    def lastTaskFailure: Option[TaskFailure] = taskFailure
  }

}
