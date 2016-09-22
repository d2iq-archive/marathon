package mesosphere.marathon.raml

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.pod.{ ContainerNetwork, MesosContainer, PodDefinition }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos
import org.scalatest.Matchers

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._

class PodStatusConversionTest extends MarathonSpec with Matchers {

  import PodStatusConversionTest._

  test("multiple tasks with multiple networks convert to proper network status") {

    def fakeNetworks(netmap: Map[String, String]): Seq[Protos.NetworkInfo] = netmap.map { entry =>
      val (name, ip) = entry
      Protos.NetworkInfo.newBuilder()
        .setName(name)
        .addIpAddresses(Protos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ip))
        .build()
    }(collection.breakOut)

    def fakeTask(networks: Seq[Protos.NetworkInfo]) = {
      val taskId = Task.Id.forRunSpec(PathId.empty)
      Task.LaunchedEphemeral(
        taskId = taskId,
        agentInfo = Instance.AgentInfo("", None, Seq.empty),
        status = Task.Status(
          stagedAt = Timestamp.zero,
          mesosStatus = Some(Protos.TaskStatus.newBuilder()
            .setTaskId(taskId.mesosTaskId)
            .setState(Protos.TaskState.TASK_UNKNOWN)
            .setContainerStatus(Protos.ContainerStatus.newBuilder()
              .addAllNetworkInfos(networks.asJava).build())
            .build()),
          taskStatus = InstanceStatus.Finished
        ),
        runSpecVersion = Timestamp.zero,
        hostPorts = Seq.empty)
    }

    val tasksWithNetworks: Seq[Task] = Seq(
      fakeTask(fakeNetworks(Map("abc" -> "1.2.3.4", "def" -> "5.6.7.8"))),
      fakeTask(fakeNetworks(Map("abc" -> "1.2.3.4", "def" -> "5.6.7.8")))
    )
    val result: Seq[NetworkStatus] = networkStatuses(tasksWithNetworks)
    val expected: Seq[NetworkStatus] = Seq(
      NetworkStatus(name = Some("abc"), addresses = Seq("1.2.3.4")),
      NetworkStatus(name = Some("def"), addresses = Seq("5.6.7.8"))
    )
    result.size should be(expected.size)
    result.toSet should be(expected.toSet)
  }

  test("ephemeral pod launched, no official Mesos status yet") {
    implicit val clock = ConstantClock()
    val pod = basicOneContainerPod.copy(version = clock.now())

    clock += 1.seconds
    val fixture = createdInstance(pod)

    val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
    status.id should be(fixture.instance.instanceId.idString)
    status.agentHostname should be(Some("agent1"))
    status.status should be(PodInstanceState.Pending)
    status.containers should be(Seq(
      ContainerStatus(
        name = "ct1",
        status = "TASK_STAGING",
        statusSince = fixture.since.toOffsetDateTime,
        containerId = Some(fixture.taskIds.head.idString),
        endpoints = Seq(
          ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
          ContainerEndpointStatus(name = "web")
        ),
        lastUpdated = fixture.since.toOffsetDateTime,
        lastChanged = fixture.since.toOffsetDateTime
      )
    ))
  }

  test("ephemeral pod launched, received STAGING status from Mesos") {
    implicit val clock = ConstantClock()
    val pod = basicOneContainerPod.copy(version = clock.now())

    clock += 1.seconds
    val fixture = stagingInstance(pod)

    val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
    status.id should be(fixture.instance.instanceId.idString)
    status.agentHostname should be(Some("agent1"))
    status.status should be(PodInstanceState.Staging)
    status.containers should be(Seq(
      ContainerStatus(
        name = "ct1",
        status = "TASK_STAGING",
        statusSince = fixture.since.toOffsetDateTime,
        containerId = Some(fixture.taskIds.head.idString),
        endpoints = Seq(
          ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
          ContainerEndpointStatus(name = "web")
        ),
        lastUpdated = fixture.since.toOffsetDateTime,
        lastChanged = fixture.since.toOffsetDateTime
      )
    ))
    status.networks should be('empty)
  }

  test("ephemeral pod launched, received STARTING status from Mesos") {
    implicit val clock = ConstantClock()
    val pod = basicOneContainerPod.copy(version = clock.now())

    clock += 1.seconds
    val fixture = startingInstance(pod)

    val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
    status.id should be(fixture.instance.instanceId.idString)
    status.agentHostname should be(Some("agent1"))
    status.status should be(PodInstanceState.Staging)
    status.containers should be(Seq(
      ContainerStatus(
        name = "ct1",
        status = "TASK_STARTING",
        statusSince = fixture.since.toOffsetDateTime,
        containerId = Some(fixture.taskIds.head.idString),
        endpoints = Seq(
          ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
          ContainerEndpointStatus(name = "web")
        ),
        lastUpdated = fixture.since.toOffsetDateTime,
        lastChanged = fixture.since.toOffsetDateTime
      )
    ))
    status.networks.toSet should be(Set(
      NetworkStatus(Some("dcos"), Seq("1.2.3.4")),
      NetworkStatus(Some("bigdog"), Seq("2.3.4.5"))
    ))
  }
}

object PodStatusConversionTest {
  val basicOneContainerPod = PodDefinition(
    id = PathId("/foo"),
    containers = Seq(
      MesosContainer(
        name = "ct1",
        resources = Resources(cpus = 0.01, mem = 100),
        image = Some(Image(kind = ImageType.Docker, id = "busybox")),
        endpoints = Seq(
          Endpoint(name = "web", containerPort = Some(80)),
          Endpoint(name = "admin", containerPort = Some(90), hostPort = Some(0))
        ),
        healthCheck = Some(HealthCheck(http = Some(HttpHealthCheck(endpoint = "web", path = Some("/ping")))))
      )
    ),
    networks = Seq(ContainerNetwork(name = "dcos"), ContainerNetwork("bigdog"))
  )

  case class InstanceFixture(
    since: Timestamp,
    agentInfo: Instance.AgentInfo,
    taskIds: Seq[Task.Id],
    instance: Instance)

  def createdInstance(pod: PodDefinition)(implicit clock: ConstantClock): InstanceFixture =
    fakeInstance(pod, InstanceStatus.Created, InstanceStatus.Created)

  def stagingInstance(pod: PodDefinition)(implicit clock: ConstantClock): InstanceFixture =
    fakeInstance(pod, InstanceStatus.Staging, InstanceStatus.Staging, Some(Protos.TaskState.TASK_STAGING))

  def startingInstance(pod: PodDefinition)(implicit clock: ConstantClock): InstanceFixture =
    fakeInstance(pod, InstanceStatus.Starting, InstanceStatus.Starting, Some(Protos.TaskState.TASK_STARTING),
      Some(Map("dcos" -> "1.2.3.4", "bigdog" -> "2.3.4.5")))

  def fakeInstance(
    pod: PodDefinition,
    instanceStatus: InstanceStatus,
    taskStatus: InstanceStatus,
    maybeTaskState: Option[Protos.TaskState] = None,
    maybeNetworks: Option[Map[String, String]] = None)(implicit clock: ConstantClock): InstanceFixture = {
    val since = clock.now()
    val agentInfo = Instance.AgentInfo("agent1", None, Seq.empty)
    val instanceId = Instance.Id.forRunSpec(pod.id)
    val taskIds = pod.containers.map { container =>
      Task.Id.forInstanceId(instanceId, Some(container))
    }

    val mesosStatus = maybeTaskState.map { taskState =>
      val statusProto = Protos.TaskStatus.newBuilder()
        .setState(taskState)
        .setTaskId(taskIds.head.mesosTaskId)

      maybeNetworks.foreach { networks =>
        statusProto.setContainerStatus(Protos.ContainerStatus.newBuilder()
          .addAllNetworkInfos(networks.map { entry =>
            val (networkName, ipAddress) = entry
            Protos.NetworkInfo.newBuilder().addIpAddresses(
              Protos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ipAddress)
            ).setName(networkName).build()
          }.asJava).build()
        ).build()
      }

      statusProto.build()
    }

    val instance: Instance = Instance(
      instanceId = instanceId,
      agentInfo = agentInfo,
      state = Instance.InstanceState(
        status = instanceStatus,
        since = since,
        version = pod.version,
        healthy = None),
      tasksMap = Seq[Task](
        Task.LaunchedEphemeral(
          taskIds.head,
          agentInfo,
          since,
          Task.Status(
            stagedAt = since,
            startedAt = if (taskStatus == InstanceStatus.Created) None else Some(since),
            mesosStatus = mesosStatus,
            taskStatus = taskStatus
          ),
          hostPorts = Seq(1001)
        )
      ).map(t => t.taskId -> t).toMap)

    InstanceFixture(since, agentInfo, taskIds, instance)
  } // fakeInstance
}
