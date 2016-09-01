package mesosphere.marathon.core.task.termination.impl

import akka.Done
import akka.actor.{ ActorRef, ActorSystem }
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit, TestProbe }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.event.MesosStatusUpdateEvent
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.task.termination.TaskKillConfig
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, InstanceTracker }
import mesosphere.marathon.core.task.{ MarathonTaskStatus, Task, TaskStateOp }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.Mockito
import org.apache.mesos
import org.apache.mesos.SchedulerDriver
import org.mockito.ArgumentCaptor
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, FunSuiteLike, GivenWhenThen, Matchers }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.concurrent.duration._

class TaskKillServiceActorTest extends TestKit(ActorSystem("test"))
    with FunSuiteLike
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with GivenWhenThen
    with ScalaFutures
    with Matchers
    with ImplicitSender
    with Mockito {

  import TaskKillServiceActorTest.log

  ignore("Kill single known task - https://github.com/mesosphere/marathon/issues/4202") {
    val f = new Fixture
    val actor = f.createTaskKillActor()

    Given("a single, known running task")
    val task = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_RUNNING)

    When("the service is asked to kill that task")
    val promise = Promise[Done]()
    actor ! TaskKillServiceActor.KillTasks(Seq(task), promise)

    Then("a kill is issued to the driver")
    verify(f.driver, timeout(500)).killTask(task.id.mesosTaskId)

    When("a terminal status update is published via the event stream")
    f.publishStatusUpdate(task.id, mesos.Protos.TaskState.TASK_KILLED)

    Then("the promise is eventually completed successfully")
    promise.future.futureValue should be (Done)
  }

  test("Kill unknown task") {
    // TODO
    val f = new Fixture
    val actor = f.createTaskKillActor()

    Given("an unknown taskId")
    val taskId = Instance.Id.forRunSpec(PathId("/unknown"))

    When("the service is asked to kill that taskId")
    val promise = Promise[Done]()
    actor ! TaskKillServiceActor.KillUnknownTaskById(taskId, promise)

    Then("it will not fetch the task from the taskTracker")
    noMoreInteractions(f.taskTracker)

    And("a kill is issued to the driver")
    verify(f.driver, timeout(500)).killTask(taskId.mesosTaskId)
    noMoreInteractions(f.driver)

    When("a terminal status update is published via the event stream")
    f.publishStatusUpdate(taskId, mesos.Protos.TaskState.TASK_KILLED)

    Then("the promise is eventually completed successfully")
    promise.future.futureValue should be (Done)
  }

  test("Kill single known LOST task") {
    val f = new Fixture
    val actor = f.createTaskKillActor()

    Given("a single, known running task")
    val task = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_LOST)

    When("the service is asked to kill that task")
    val promise = Promise[Done]()
    actor ! TaskKillServiceActor.KillTasks(Seq(task), promise)

    Then("NO kill is issued to the driver because the task is lost")
    noMoreInteractions(f.driver)

    And("the stateOpProcessor receives an expunge")
    verify(f.stateOpProcessor, timeout(500)).process(TaskStateOp.ForceExpunge(task.id))

    When("a terminal status update is published via the event stream")
    f.publishStatusUpdate(task.id, mesos.Protos.TaskState.TASK_KILLED)

    Then("the promise is eventually completed successfully")
    promise.future.futureValue should be (Done)
  }

  ignore("kill multiple tasks at once - https://github.com/mesosphere/marathon/issues/4202") {
    val f = new Fixture
    val actor = f.createTaskKillActor()

    Given("a list of tasks")
    val runningTask = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_RUNNING)
    val lostTask = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_LOST)
    val stagingTask = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_STAGING)

    When("the service is asked to kill those tasks")
    val promise = Promise[Done]()
    actor ! TaskKillServiceActor.KillTasks(Seq(runningTask, lostTask, stagingTask), promise)

    Then("the task tracker is not queried")
    noMoreInteractions(f.taskTracker)

    And("three kill requests are issued to the driver")
    verify(f.driver, timeout(500)).killTask(runningTask.id.mesosTaskId)
    verify(f.stateOpProcessor, timeout(500)).process(TaskStateOp.ForceExpunge(lostTask.id))
    verify(f.driver, timeout(500)).killTask(stagingTask.id.mesosTaskId)
    noMoreInteractions(f.driver)

    And("Eventually terminal status updates are published via the event stream")
    f.publishStatusUpdate(runningTask.id, mesos.Protos.TaskState.TASK_KILLED)
    f.publishStatusUpdate(lostTask.id, mesos.Protos.TaskState.TASK_LOST)
    f.publishStatusUpdate(stagingTask.id, mesos.Protos.TaskState.TASK_LOST)

    Then("the promise is eventually completed successfully")
    promise.future.futureValue should be (Done)
  }

  test("kill multiple tasks at once (empty list)") {
    val f = new Fixture
    val actor = f.createTaskKillActor()

    Given("an empty list")
    val emptyList = Seq.empty[Task]

    When("the service is asked to kill those tasks")
    val promise = Promise[Done]()
    actor ! TaskKillServiceActor.KillTasks(emptyList, promise)

    Then("the promise is eventually completed successfully")
    promise.future.futureValue should be (Done)

    And("the task tracker is not queried")
    noMoreInteractions(f.taskTracker)

    And("no kill is issued")
    noMoreInteractions(f.driver)
  }

  ignore("kill multiple tasks subsequently - https://github.com/mesosphere/marathon/issues/4202") {
    val f = new Fixture
    val actor = f.createTaskKillActor()

    Given("multiple tasks")
    val task1 = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_RUNNING)
    val task2 = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_RUNNING)
    val task3 = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_RUNNING)

    val promise1 = Promise[Done]()
    val promise2 = Promise[Done]()
    val promise3 = Promise[Done]()

    When("the service is asked subsequently to kill those tasks")
    actor ! TaskKillServiceActor.KillTasks(Seq(task1), promise1)
    actor ! TaskKillServiceActor.KillTasks(Seq(task2), promise2)
    actor ! TaskKillServiceActor.KillTasks(Seq(task3), promise3)

    Then("exactly 3 kills are issued to the driver")
    verify(f.driver, timeout(500)).killTask(task1.id.mesosTaskId)
    verify(f.driver, timeout(500)).killTask(task2.id.mesosTaskId)
    verify(f.driver, timeout(500)).killTask(task3.id.mesosTaskId)
    noMoreInteractions(f.driver)

    And("Eventually terminal status updates are published via the event stream")
    f.publishStatusUpdate(task1.id, mesos.Protos.TaskState.TASK_KILLED)
    f.publishStatusUpdate(task2.id, mesos.Protos.TaskState.TASK_KILLED)
    f.publishStatusUpdate(task3.id, mesos.Protos.TaskState.TASK_KILLED)

    Then("the promises are eventually are completed successfully")
    promise1.future.futureValue should be (Done)
    promise2.future.futureValue should be (Done)
    promise3.future.futureValue should be (Done)
  }

  test("killing tasks is throttled (single requests)") {
    val f = new Fixture
    val actor = f.createTaskKillActor()

    Given("multiple tasks")
    val tasks: Map[Instance.Id, Task] = (1 to 10).map { index =>
      val task = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_RUNNING)
      task.id -> task
    }(collection.breakOut)

    When("the service is asked to kill those tasks")
    tasks.values.foreach { task =>
      actor ! TaskKillServiceActor.KillTasks(Seq(task), Promise[Done]())
    }

    Then("5 kills are issued immediately to the driver")
    val captor: ArgumentCaptor[mesos.Protos.TaskID] = ArgumentCaptor.forClass(classOf[mesos.Protos.TaskID])
    verify(f.driver, timeout(5000).times(5)).killTask(captor.capture())
    reset(f.driver)

    And("after receiving terminal messages for the requested kills, 5 additional tasks are killed")
    captor.getAllValues.asScala.foreach { id =>
      val taskId = Instance.Id(id)
      tasks.get(taskId).foreach { task =>
        f.publishStatusUpdate(task.id, mesos.Protos.TaskState.TASK_KILLED)
      }
    }

    verify(f.driver, timeout(500).times(5)).killTask(any)
    noMoreInteractions(f.driver)
  }

  test("killing tasks is throttled (batch request)") {
    val f = new Fixture
    val actor = f.createTaskKillActor()

    Given("multiple tasks")
    val tasks: Map[Instance.Id, Task] = (1 to 10).map { index =>
      val task = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_RUNNING)
      task.id -> task
    }(collection.breakOut)

    When("the service is asked to kill those tasks")
    val promise = Promise[Done]()
    actor ! TaskKillServiceActor.KillTasks(tasks.values, promise)

    Then("5 kills are issued immediately to the driver")
    val captor: ArgumentCaptor[mesos.Protos.TaskID] = ArgumentCaptor.forClass(classOf[mesos.Protos.TaskID])
    verify(f.driver, timeout(5000).times(5)).killTask(captor.capture())
    reset(f.driver)

    And("after receiving terminal messages for the requested kills, 5 additional tasks are killed")
    captor.getAllValues.asScala.foreach { id =>
      val taskId = Instance.Id(id)
      tasks.get(taskId).foreach { task =>
        f.publishStatusUpdate(task.id, mesos.Protos.TaskState.TASK_KILLED)
      }
    }

    verify(f.driver, timeout(2000).times(5)).killTask(any)
    noMoreInteractions(f.driver)
  }

  test("kills will be retried") {
    val f = new Fixture
    val actor = f.createTaskKillActor(f.retryConfig)

    Given("a single, known running task")
    val task = f.mockTask(Instance.Id.forRunSpec(f.appId), f.now(), mesos.Protos.TaskState.TASK_RUNNING)
    val promise = Promise[Done]()

    When("the service is asked to kill that task")
    actor ! TaskKillServiceActor.KillTasks(Seq(task), promise)

    Then("a kill is issued to the driver")
    verify(f.driver, timeout(500)).killTask(task.id.mesosTaskId)

    When("no statusUpdate is received and we reach the future")
    f.clock.+=(10.seconds)

    Then("the service will eventually retry")
    verify(f.driver, timeout(1000)).killTask(task.id.mesosTaskId)

    When("no statusUpdate is received and we reach the future")
    f.clock.+=(10.seconds)

    Then("the service will eventually expunge the task if it reached the max attempts")
    verify(f.stateOpProcessor, timeout(1000)).process(TaskStateOp.ForceExpunge(task.id))

    When("a terminal status update is published via the event stream")
    f.publishStatusUpdate(task.id, mesos.Protos.TaskState.TASK_KILLED)

    Then("the promise is eventually completed successfully")
    promise.future.futureValue should be (Done)
  }

  override protected def afterAll(): Unit = {
    shutdown()
  }

  override protected def afterEach(): Unit = {
    import TaskKillServiceActorTest._
    actor match {
      case Some(actorRef) => system.stop(actorRef)
      case _ =>
        val msg = "The test didn't set a reference to the tested actor. Either make sure to set the ref" +
          "so it can be stopped automatically, or move the test to a suite that doesn't test this actor."
        fail(msg)
    }
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(10, Seconds)))

  class Fixture {
    import scala.concurrent.duration._

    val appId = PathId("/test")
    val taskTracker: InstanceTracker = mock[InstanceTracker]
    val driver = mock[SchedulerDriver]
    val driverHolder: MarathonSchedulerDriverHolder = {
      val holder = new MarathonSchedulerDriverHolder
      holder.driver = Some(driver)
      holder
    }
    val defaultConfig: TaskKillConfig = new TaskKillConfig {
      override lazy val killChunkSize: Int = 5
      override lazy val killRetryTimeout: FiniteDuration = 10.minutes
      override lazy val killRetryMax: Int = 5
    }
    val retryConfig: TaskKillConfig = new TaskKillConfig {
      override lazy val killChunkSize: Int = 5
      override lazy val killRetryTimeout: FiniteDuration = 500.millis
      override lazy val killRetryMax: Int = 1
    }
    val stateOpProcessor: TaskStateOpProcessor = mock[TaskStateOpProcessor]
    val parent = TestProbe()
    val clock = ConstantClock()

    def createTaskKillActor(config: TaskKillConfig = defaultConfig): ActorRef = {
      import TaskKillServiceActorTest._
      val actorRef: ActorRef = TestActorRef(TaskKillServiceActor.props(taskTracker, driverHolder, stateOpProcessor, config, clock), parent.ref, "TaskKillService")
      actor = Some(actorRef)
      actorRef
    }

    def mockTask(taskId: Instance.Id, stagedAt: Timestamp, mesosState: mesos.Protos.TaskState): Task.LaunchedEphemeral = {
      val status: Task.Status = mock[Task.Status]
      status.stagedAt returns stagedAt
      val mesosStatus: mesos.Protos.TaskStatus = mesos.Protos.TaskStatus.newBuilder()
        .setState(mesosState)
        .buildPartial()
      val task = mock[Task.LaunchedEphemeral]
      task.id returns taskId
      task.status returns status
      task.mesosStatus returns Some(mesosStatus)
      status.taskStatus returns MarathonTaskStatus(mesosStatus)
      task.isGone returns status.taskStatus == InstanceStatus.Gone
      task
    }
    def now(): Timestamp = Timestamp(0)
    def publishStatusUpdate(taskId: Instance.Id, state: mesos.Protos.TaskState): Unit = {
      val appId = taskId.runSpecId
      val statusUpdateEvent =
        MesosStatusUpdateEvent(
          slaveId = "", taskId = taskId, taskStatus = state.toString, message = "", appId = appId, host = "",
          ipAddresses = None, ports = Nil, version = "version"
        )
      log.info("publish {} on the event stream", statusUpdateEvent)
      system.eventStream.publish(statusUpdateEvent)
    }
  }
}

object TaskKillServiceActorTest {
  val log = LoggerFactory.getLogger(getClass)
  var actor: Option[ActorRef] = None
}