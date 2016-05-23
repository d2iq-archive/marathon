package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ ActorRef, Status }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.bus.MesosTaskStatus
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.impl.TaskOpProcessor.Action
import mesosphere.marathon.core.task.tracker.impl.TaskOpProcessorImpl.StatusUpdateActionResolver
import mesosphere.marathon.state.{ PathId, TaskRepository }
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

private[tracker] object TaskOpProcessorImpl {

  /**
    * Maps a task status update to the appropriate [[TaskOpProcessor.Action]].
    *
    * @param directTaskTracker a TaskTracker instance that goes directly to the correct taskTracker
    *                          without going through the WhenLeaderActor indirection.
    */
  class StatusUpdateActionResolver(directTaskTracker: TaskTracker) {
    private[this] val log = LoggerFactory.getLogger(getClass)

    /**
      * Maps the UpdateStatus action to
      *
      * * a Action.Fail if the task does not exist OR ELSE
      * * a Action.Noop if the task does not have to be changed OR ELSE
      * * an Action.Expunge if the TaskStatus update indicates a terminated task OR ELSE
      * * an Action.Update if the tasks existed and the TaskStatus contains new information OR ELSE
      */
    def resolve(
      appId: PathId, taskId: String, status: TaskStatus)(
        implicit ec: ExecutionContext): Future[Action] = {
      directTaskTracker.task(appId, taskId).map {
        case Some(existingTask) =>
          resolveForExistingTask(existingTask, status)
        case None =>
          Action.Fail(new IllegalStateException(s"task [$taskId] of app [$appId] does not exist"))
      }
    }

    /**
      * Calculates the change that needs to performed on this task according to the given task status update
      */
    private[impl] def resolveForExistingTask(task: MarathonTask, status: TaskStatus): Action = {
      def updateTaskOnStateChange(task: MarathonTask): Action = {
        def statusDidChange(statusA: TaskStatus, statusB: TaskStatus): Boolean = {
          val healthy = statusB.hasHealthy &&
            (!statusA.hasHealthy || statusA.getHealthy != statusB.getHealthy)

          healthy || statusA.getState != statusB.getState
        }

        if (statusDidChange(task.getStatus, status)) {
          Action.Update(task.toBuilder.setStatus(status).build())
        }
        else {
          log.debug(s"Ignoring status update for ${task.getId}. Status did not change.")
          Action.Noop
        }
      }

      status match {
        case MesosTaskStatus.Terminal(_) =>
          Action.Expunge
        case MesosTaskStatus.Running(_) if task.getStatus.getState != TaskState.TASK_RUNNING => // was not running
          Action.Update(task.toBuilder.setStartedAt(System.currentTimeMillis).setStatus(status).build())
        case MesosTaskStatus.TemporarilyUnreachable(_) =>
          updateTaskOnStateChange(task)
        case _ =>
          updateTaskOnStateChange(task)
      }
    }

  }
}
/**
  * Processes durable operations on tasks by
  *
  * * storing the updated tasks in the task repository
  * * informing the taskTracker actor of the latest task state
  */
private[tracker] class TaskOpProcessorImpl(
    taskTrackerRef: ActorRef,
    repo: TaskRepository,
    statusUpdateActionResolver: StatusUpdateActionResolver) extends TaskOpProcessor {
  private[this] val log = LoggerFactory.getLogger(getClass)

  import TaskOpProcessor._

  override def process(op: Operation)(implicit ec: ExecutionContext): Future[Unit] = {
    op.action match {

      case Action.Update(task) =>
        // Used for a create or as a result from a UpdateStatus action.
        // The update is propagated to the taskTracker which in turn informs the sender about the success (see Ack).
        repo.store(task).map { _ =>
          taskTrackerRef ! TaskTrackerActor.TaskUpdated(op.appId, task, TaskTrackerActor.Ack(op.sender))
        }.recoverWith(tryToRecover(op)(expectedTaskState = Some(task)))
      case Action.Expunge =>
        // Used for task termination or as a result from a UpdateStatus action.
        // The expunge is propagated to the taskTracker which in turn informs the sender about the success (see Ack).
        repo.expunge(op.taskId).map { _ =>
          taskTrackerRef ! TaskTrackerActor.TaskRemoved(op.appId, op.taskId, TaskTrackerActor.Ack(op.sender))
        }.recoverWith(tryToRecover(op)(expectedTaskState = None))

      case Action.UpdateStatus(status) =>
        statusUpdateActionResolver.resolve(op.appId, op.taskId, status).flatMap { action: Action =>
          // Since this action is mapped to another action, we delegate the responsibility to inform
          // the sender to that other action.
          process(op.copy(action = action))
        }

      case Action.Noop =>
        // Used if a task status update does not result in any changes.
        // Since we did not change the task state, we inform the sender directly of the success of
        // the operation.
        op.sender ! (())
        Future.successful(())
      case Action.Fail(cause) =>
        // Used if a task status update for a non-existing task is processed.
        // Since we did not change the task state, we inform the sender directly of the failed operation.
        op.sender ! Status.Failure(cause)
        Future.successful(())
    }
  }

  /**
    * If we encounter failure, we try to reload the effected task to make sure that the taskTracker
    * is up-to-date. We signal failure to the sender if the state is not as expected.
    *
    * If reloading the tasks also fails, the operation does fail.
    *
    * This tries to isolated failures that only effect certain tasks, e.g. errors in the serialization logic
    * which are only triggered for a certain combination of fields.
    */
  private[this] def tryToRecover(
    op: Operation)(
      expectedTaskState: Option[MarathonTask])(
        implicit ec: ExecutionContext): PartialFunction[Throwable, Future[Unit]] = {

    case NonFatal(cause) =>
      def ack(actualTaskState: Option[MarathonTask]): TaskTrackerActor.Ack = {
        val msg = if (expectedTaskState == actualTaskState) (()) else Status.Failure(cause)
        TaskTrackerActor.Ack(op.sender, msg)
      }

      log.warn(
        s"task [${op.taskId}] of app [${op.appId}]: try to recover from failed ${op.action.toString}", cause
      )

      repo.task(op.taskId).map {
        case Some(task) =>
          taskTrackerRef ! TaskTrackerActor.TaskUpdated(op.appId, task, ack(Some(task)))
        case None =>
          taskTrackerRef ! TaskTrackerActor.TaskRemoved(op.appId, op.taskId, ack(None))
      }.recover {
        case NonFatal(loadingFailure) =>
          log.warn(
            s"task [${op.taskId}] of app [${op.appId}]: task reloading failed as well", loadingFailure
          )
          throw cause
      }
  }
}
