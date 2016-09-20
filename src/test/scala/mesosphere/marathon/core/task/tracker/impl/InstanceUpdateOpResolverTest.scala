package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.{ InstanceConversions, MarathonTestHelper }
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.bus.{ MesosTaskStatusTestHelper, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.impl.InstanceOpProcessorImpl.InstanceUpdateOpResolver
import mesosphere.marathon.core.task.{ MarathonTaskStatus, Task }
import mesosphere.marathon.core.task.state.MarathonTaskStatusMapping
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.Mockito
import org.apache.mesos
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.Future

/**
  * Some specialized tests for statusUpdate action resolving.
  *
  * More tests are in [[mesosphere.marathon.tasks.InstanceTrackerImplTest]]
  */
class InstanceUpdateOpResolverTest
    extends FunSuite with Mockito with GivenWhenThen with ScalaFutures with Matchers with InstanceConversions {
  import scala.concurrent.ExecutionContext.Implicits.global

  test("ForceExpunge results in NoChange if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.instance(f.notExistingTaskId) returns Future.successful(None)

    When("A ForceExpunge is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.ForceExpunge(f.notExistingTaskId)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.notExistingTaskId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Noop]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("LaunchOnReservation fails if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.instance(f.notExistingTaskId) returns Future.successful(None)

    When("A LaunchOnReservation is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.LaunchOnReservation(
      instanceId = f.notExistingTaskId,
      runSpecVersion = Timestamp(0),
      timestamp = Timestamp(0),
      status = Task.Status(Timestamp(0), taskStatus = InstanceStatus.Running),
      hostPorts = Seq.empty)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.notExistingTaskId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  // this case is actually a little constructed, as the task will be loaded before and will fail if it doesn't exist
  test("MesosUpdate fails if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.instance(f.existingTask.instanceId) returns Future.successful(None)

    When("A MesosUpdate is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.MesosUpdate(
      instance = f.existingTask,
      mesosStatus = MesosTaskStatusTestHelper.running,
      now = Timestamp(0))).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.existingTask.instanceId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  for (
    reason <- MarathonTaskStatusMapping.Unreachable
  ) {
    test(s"a TASK_LOST update with $reason indicating a TemporarilyUnreachable task is mapped to an update") {
      val f = new Fixture

      Given("an existing task")
      f.taskTracker.instance(f.existingTask.taskId) returns Future.successful(Some(f.existingTask))

      When("A TASK_LOST update is received with a reason indicating it might come back")
      val operation = TaskStatusUpdateTestHelper.lost(reason, f.existingTask).operation
      val effect = f.stateOpResolver.resolve(operation).futureValue

      Then("taskTracker.task is called")
      verify(f.taskTracker).instance(f.existingTask.taskId)

      And("the result is an Update")
      effect shouldBe a[InstanceUpdateEffect.Update]

      And("the new state should have the correct status")
      val update: InstanceUpdateEffect.Update = effect.asInstanceOf[InstanceUpdateEffect.Update]
      update.instance.isUnreachable should be (true)

      And("there are no more interactions")
      f.verifyNoMoreInteractions()
    }
  }

  for (
    reason <- MarathonTaskStatusMapping.Gone
  ) {
    test(s"a TASK_LOST update with $reason indicating a task won't come back is mapped to an expunge") {
      val f = new Fixture

      Given("an existing instance")
      f.taskTracker.instance(f.existingInstance.instanceId) returns Future.successful(Some(f.existingInstance))

      When("A TASK_LOST update is received with a reason indicating it won't come back")
      val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.existingTask).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
      val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

      Then("taskTracker.task is called")
      verify(f.taskTracker).instance(f.existingTask.taskId)

      And("the result is an Expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Expunge]

      // TODO(PODS): in order to be able to compare the instances, we need to tediously create a copy here
      // it should be verified elsewhere (in a unit test) that updating is done correctly both on task level
      // and on instance level, then it'd be enough here to check that the operation results in an
      // InstanceUpdateEffect.Expunge of the expected instanceId
      val updatedTask = f.existingTask.copy(status = f.existingTask.status.copy(
        mesosStatus = Some(stateOp.mesosStatus),
        taskStatus = MarathonTaskStatus(stateOp.mesosStatus)
      ))
      val updatedTasksMap = f.existingInstance.tasksMap.updated(updatedTask.taskId, updatedTask)
      val expectedState = f.existingInstance.copy(
        state = f.existingInstance.state.copy(
          status = MarathonTaskStatus(stateOp.mesosStatus),
          since = stateOp.now
        ),
        tasksMap = updatedTasksMap
      )

      stateChange shouldEqual InstanceUpdateEffect.Expunge(expectedState)

      And("there are no more interactions")
      f.verifyNoMoreInteractions()
    }
  }

  for (
    reason <- MarathonTaskStatusMapping.Unreachable
  ) {
    test(s"a TASK_LOST update with an unreachable $reason but a message saying that the task is unknown to the slave is mapped to an expunge") {
      val f = new Fixture

      Given("an existing task")
      f.taskTracker.instance(f.existingTask.taskId.instanceId) returns Future.successful(Some(f.existingTask))

      When("A TASK_LOST update is received indicating the agent is unknown")
      val message = "Reconciliation: Task is unknown to the slave"
      val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.existingTask, Some(message)).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
      val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

      Then("taskTracker.task is called")
      verify(f.taskTracker).instance(f.existingTask.taskId.instanceId)

      And("the result is an expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Expunge]
      And("there are no more interactions")
      f.verifyNoMoreInteractions()
    }
  }

  test("a subsequent TASK_LOST update with another reason is mapped to a noop and will not update the timestamp") {
    val f = new Fixture

    Given("an existing lost task")
    f.taskTracker.instance(f.existingLostTask.taskId) returns Future.successful(Some(f.existingLostTask))

    When("A subsequent TASK_LOST update is received")
    val reason = mesos.Protos.TaskStatus.Reason.REASON_SLAVE_DISCONNECTED
    val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.existingLostTask).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
    val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.existingLostTask.taskId)

    And("the result is an noop")
    stateChange shouldBe a[InstanceUpdateEffect.Noop]
    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("a subsequent TASK_LOST update with a message saying that the task is unknown to the slave is mapped to an expunge") {
    val f = new Fixture

    Given("an existing lost task")
    f.taskTracker.instance(f.existingLostTask.taskId.instanceId) returns Future.successful(Some(f.existingLostTask))

    When("A subsequent TASK_LOST update is received indicating the agent is unknown")
    val reason = mesos.Protos.TaskStatus.Reason.REASON_RECONCILIATION
    val maybeMessage = Some("Reconciliation: Task is unknown to the slave")
    val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.existingLostTask, maybeMessage).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
    val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.existingLostTask.taskId.instanceId)

    And("the result is an expunge")
    stateChange shouldBe a[InstanceUpdateEffect.Expunge]
    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("ReservationTimeout fails if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.instance(f.notExistingTaskId) returns Future.successful(None)

    When("A MesosUpdate is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.ReservationTimeout(f.notExistingTaskId)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.notExistingTaskId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Launch fails if task already exists") {
    val f = new Fixture
    Given("an existing task")
    f.taskTracker.instance(f.existingTask.taskId) returns Future.successful(Some(f.existingTask))

    When("A LaunchEphemeral is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.LaunchEphemeral(f.existingTask)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.existingTask.taskId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Reserve fails if task already exists") {
    val f = new Fixture
    Given("an existing task")
    f.taskTracker.instance(f.existingReservedTask.taskId) returns Future.successful(Some(f.existingReservedTask))

    When("A Reserve is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.Reserve(f.existingReservedTask)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.existingReservedTask.taskId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Revert does not query the state") {
    val f = new Fixture
    Given("a Revert stateOp")

    When("the stateOp is resolved")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.Revert(f.existingReservedTask)).futureValue

    And("the result is an Update")
    stateChange shouldEqual InstanceUpdateEffect.Update(f.existingReservedTask, None)

    And("The taskTracker is not queried at all")
    f.verifyNoMoreInteractions()
  }

  class Fixture {
    val taskTracker = mock[InstanceTracker]
    val stateOpResolver = new InstanceUpdateOpResolver(taskTracker)

    val appId = PathId("/app")
    val existingTask = MarathonTestHelper.minimalTask(Task.Id.forRunSpec(appId), Timestamp.now(), None, InstanceStatus.Running)
    val existingInstance: Instance = existingTask

    val existingReservedTask = MarathonTestHelper.residentReservedTask(appId)
    val notExistingTaskId = Task.Id.forRunSpec(appId)
    val existingLostTask = MarathonTestHelper.mininimalLostTask(appId)

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
    }
  }
}
