package mesosphere.marathon

import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import org.junit.{Before, Test}
import org.junit.Assert._
import org.mockito.Mockito._
import org.mockito.Matchers._
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.state.{MarathonStore, AppRepository}
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.tasks.{TaskQueue, TaskTracker}
import org.apache.mesos.SchedulerDriver
import com.google.common.collect.Lists
import org.apache.mesos.Protos.{TaskID, TaskInfo}
import org.mockito.ArgumentCaptor
import mesosphere.marathon.Protos.MarathonTask
import scala.collection.JavaConverters._
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.util.RateLimiters
import mesosphere.marathon.health.HealthActorProxy

/**
 * @author Tobi Knaup
 */
class MarathonSchedulerTest extends AssertionsForJUnit
  with MockitoSugar with MarathonTestHelper {

  var store: MarathonStore[AppRepository] = null
  var tracker: TaskTracker = null
  var queue: TaskQueue = null
  var scheduler: MarathonScheduler = null
  var frameworkIdUtil: FrameworkIdUtil = null
  var rateLimiters: RateLimiters = null
  var healthActor: HealthActorProxy = null

  @Before
  def setupScheduler() = {
    store = mock[MarathonStore[AppRepository]]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
    frameworkIdUtil = mock[FrameworkIdUtil]
    rateLimiters = mock[RateLimiters]
    healthActor = mock[HealthActorProxy]
    scheduler = new MarathonScheduler(
      None, new ObjectMapper, healthActor, store, tracker, queue, frameworkIdUtil,
      rateLimiters)
  }

  @Test
  def testResourceOffers() {
    val driver = mock[SchedulerDriver]
    val offer = makeBasicOffer(4, 1024, 31000, 32000).build
    val offers = Lists.newArrayList(offer)
    val app = AppDefinition(
      id = "testOffers",
      executor = "//cmd",
      ports = Seq(8080)
    )

    when(tracker.newTaskId("testOffers"))
      .thenReturn(TaskID.newBuilder.setValue("testOffers_0-1234").build)
    when(tracker.checkStagedTasks).thenReturn(Seq())
    when(queue.poll()).thenReturn(app)

    scheduler.resourceOffers(driver, offers)

    val taskInfos = ArgumentCaptor.forClass(classOf[java.util.List[TaskInfo]])
    val marathonTask = ArgumentCaptor.forClass(classOf[MarathonTask])

    verify(driver).launchTasks(same(offer.getId), taskInfos.capture())
    verify(tracker).starting(same(app.id), marathonTask.capture())

    assertEquals(1, taskInfos.getValue.size())
    val taskInfoPortVar = taskInfos.getValue.get(0).getCommand.getEnvironment
      .getVariablesList.asScala.find(v => v.getName == "PORT")
    assertTrue(taskInfoPortVar.isDefined)
    val marathonTaskPort = marathonTask.getValue.getPorts(0)
    assertEquals(taskInfoPortVar.get.getValue, marathonTaskPort.toString)
  }
}
