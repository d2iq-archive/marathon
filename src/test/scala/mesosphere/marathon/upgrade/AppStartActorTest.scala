package mesosphere.marathon.upgrade

import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.event.{ InstanceHealthChanged, InstanceChanged, DeploymentStatus }
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.core.instance.{ InstanceStatus, Instance }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.{ AppStartCanceledException, MarathonSpec, MarathonTestHelper, SchedulerActions }
import org.apache.mesos.SchedulerDriver
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

class AppStartActorTest
    extends MarathonActorSupport
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with Mockito {

  test("Without Health Checks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 2, promise)
    watch(ref)

    system.eventStream.publish(f.instanceChanged(app, InstanceStatus.Running))
    system.eventStream.publish(f.instanceChanged(app, InstanceStatus.Running))

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startApp(f.driver, app.copy(instances = 2))
    expectTerminated(ref)
  }

  test("With Health Checks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/app"), instances = 10, healthChecks = Set(HealthCheck()))
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 2, promise)
    watch(ref)

    system.eventStream.publish(f.healthChanged(app, healthy = true))
    system.eventStream.publish(f.healthChanged(app, healthy = true))

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startApp(f.driver, app.copy(instances = 2))
    expectTerminated(ref)
  }

  test("Failed") {
    val f = new Fixture
    f.scheduler.stopApp(any).asInstanceOf[Future[Unit]] returns Future.successful(())

    val app = AppDefinition(id = PathId("/app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 2, promise)
    watch(ref)

    ref.stop()

    intercept[AppStartCanceledException] {
      Await.result(promise.future, 5.seconds)
    }

    verify(f.scheduler).startApp(f.driver, app.copy(instances = 2))
    verify(f.scheduler).stopApp(app)
    expectTerminated(ref)
  }

  test("No tasks to start without health checks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 0, promise)
    watch(ref)

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startApp(f.driver, app.copy(instances = 0))
    expectTerminated(ref)
  }

  test("No tasks to start with health checks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/app"), instances = 10, healthChecks = Set(HealthCheck()))
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 0, promise)
    watch(ref)

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startApp(f.driver, app.copy(instances = 0))
    expectTerminated(ref)
  }

  class Fixture {

    val driver: SchedulerDriver = mock[SchedulerDriver]
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    val taskTracker: InstanceTracker = MarathonTestHelper.createTaskTracker(AlwaysElectedLeadershipModule.forActorSystem(system))
    val deploymentManager: TestProbe = TestProbe()
    val deploymentStatus: DeploymentStatus = mock[DeploymentStatus]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    def instanceChanged(app: AppDefinition, status: InstanceStatus): InstanceChanged = {
      val instanceId = Instance.Id.forRunSpec(app.id)
      val instance: Instance = mock[Instance]
      instance.id returns instanceId
      InstanceChanged(instanceId, app.version, app.id, status, instance)
    }

    def healthChanged(app: AppDefinition, healthy: Boolean): InstanceHealthChanged = {
      InstanceHealthChanged(Instance.Id.forRunSpec(app.id), app.version, app.id, healthy = healthy)
    }

    def startActor(app: AppDefinition, scaleTo: Int, promise: Promise[Unit]): TestActorRef[AppStartActor] =
      TestActorRef(AppStartActor.props(deploymentManager.ref, deploymentStatus, driver, scheduler,
        launchQueue, taskTracker, system.eventStream, readinessCheckExecutor, app, scaleTo, promise)
      )
  }
}
