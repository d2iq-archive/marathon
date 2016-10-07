package mesosphere.marathon.core.instance

import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos

import scala.collection.immutable.Seq
import scala.concurrent.duration._

case class TestInstanceBuilder(
    instance: Instance, now: Timestamp = Timestamp.now()
) {

  def addTaskLaunched(container: Option[MesosContainer] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskLaunched(container).build()

  def addTaskReserved(reservation: Task.Reservation = TestTaskBuilder.Helper.newReservation): TestInstanceBuilder =
    addTaskWithBuilder().taskReserved(reservation).build()

  def addTaskResidentReserved(localVolumeIds: Task.LocalVolumeId*): TestInstanceBuilder =
    addTaskWithBuilder().taskResidentReserved(localVolumeIds: _*).build()

  def addTaskResidentLaunched(localVolumeIds: Task.LocalVolumeId*): TestInstanceBuilder =
    addTaskWithBuilder().taskResidentLaunched(localVolumeIds: _*).build()

  def addTaskRunning(container: Option[MesosContainer] = None, stagedAt: Timestamp = now, startedAt: Timestamp = now): TestInstanceBuilder =
    addTaskWithBuilder().taskRunning(container, stagedAt, startedAt).build()

  def addTaskUnreachable(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskUnreachable(since, containerName).build()

  def addTaskGone(since: Timestamp = now, containerName: Option[String] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskGone(since, containerName).build()

  def addTaskStaged(stagedAt: Timestamp = now, version: Option[Timestamp] = None, container: Option[MesosContainer] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskStaged(container, stagedAt, version).build()

  def addTaskStarting(stagedAt: Timestamp = now): TestInstanceBuilder =
    addTaskWithBuilder().taskStarting(stagedAt).build()

  def addTaskWithBuilder(): TestTaskBuilder = TestTaskBuilder.newBuilder(this)

  private[instance] def addTask(task: Task): TestInstanceBuilder = {
    val newBuilder = this.copy(instance = instance.updatedInstance(task, now + 1.second).copy(agentInfo = task.agentInfo))
    assert(newBuilder.getInstance().tasks.forall(_.agentInfo == task.agentInfo))
    newBuilder
  }

  def pickFirstTask[T <: Task](): T = instance.tasks.headOption.getOrElse(throw new RuntimeException("No matching Task in Instance")).asInstanceOf[T]

  def getInstance() = instance

  def stateOpLaunch() = InstanceUpdateOperation.LaunchEphemeral(instance)

  def stateOpUpdate(mesosStatus: mesos.Protos.TaskStatus) = InstanceUpdateOperation.MesosUpdate(instance, mesosStatus, now)

  def taskLaunchedOp(): InstanceUpdateOperation.LaunchOnReservation = {
    InstanceUpdateOperation.LaunchOnReservation(instanceId = instance.instanceId, timestamp = now, runSpecVersion = instance.runSpecVersion, status = Task.Status(stagedAt = now, taskStatus = InstanceStatus.Running), hostPorts = Seq.empty)
  }

  def stateOpExpunge() = InstanceUpdateOperation.ForceExpunge(instance.instanceId)

  def stateOpReservationTimeout() = InstanceUpdateOperation.ReservationTimeout(instance.instanceId)
}

object TestInstanceBuilder {

  def emptyInstance(now: Timestamp = Timestamp.now(), version: Timestamp = Timestamp.zero, instanceId: Instance.Id): Instance = Instance(
    instanceId = instanceId,
    agentInfo = TestInstanceBuilder.defaultAgentInfo,
    state = InstanceState(InstanceStatus.Created, now, version, healthy = None),
    tasksMap = Map.empty
  )

  private val defaultAgentInfo = Instance.AgentInfo(host = "host.some", agentId = None, attributes = Seq.empty)

  def newBuilder(runSpecId: PathId, now: Timestamp = Timestamp.now(), version: Timestamp = Timestamp.zero): TestInstanceBuilder = newBuilderWithInstanceId(Instance.Id.forRunSpec(runSpecId), now, version)

  def newBuilderWithInstanceId(instanceId: Instance.Id, now: Timestamp = Timestamp.now(), version: Timestamp = Timestamp.zero): TestInstanceBuilder = TestInstanceBuilder(emptyInstance(now, version, instanceId), now)

  def newBuilderWithLaunchedTask(runSpecId: PathId, now: Timestamp = Timestamp.now(), version: Timestamp = Timestamp.zero): TestInstanceBuilder = newBuilder(runSpecId, now, version).addTaskLaunched()
}
