package mesosphere.marathon.upgrade

import org.apache.mesos.SchedulerDriver
import akka.event.EventStream
import mesosphere.marathon.Protos.MarathonTask
import akka.actor.{ Actor, ActorLogging }
import scala.concurrent.Promise
import mesosphere.marathon.event.MesosStatusUpdateEvent
import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.TaskUpgradeCanceledException
import scala.collection.mutable

class TaskKillActor(
    driver: SchedulerDriver,
    eventBus: EventStream,
    tasksToKill: Set[MarathonTask],
    promise: Promise[Boolean]) extends Actor with ActorLogging {

  val idsToKill = tasksToKill.map(_.getId).to[mutable.Set]

  override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    log.info(s"Killing ${tasksToKill.size} instances")
    for (task <- tasksToKill)
      driver.killTask(taskId(task.getId))

    checkFinished()
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
  }

  val taskFinished = "^TASK_(FINISHED|LOST|KILLED)$".r
  def receive = {
    case MesosStatusUpdateEvent(_, taskId, taskFinished(_), _, _, _, _, _, _) if idsToKill(taskId) =>
      idsToKill.remove(taskId)
      log.info(s"Task $taskId has been killed. Waiting for ${idsToKill.size} more tasks to be killed.")
      checkFinished()

    case x: MesosStatusUpdateEvent => log.debug(s"Received $x")
  }

  def checkFinished(): Unit =
    if (idsToKill.size == 0) {
      log.info("Successfully killed all the tasks")
      promise.success(true)
      context.stop(self)
    }

  private def taskId(id: String) = TaskID.newBuilder().setValue(id).build()
}
