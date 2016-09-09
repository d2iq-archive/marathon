package mesosphere.marathon.core.task.jobs.impl

import akka.actor._
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskReservationTimeoutHandler }
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.instance.Instance
import org.apache.mesos.Protos.TaskState
import org.slf4j.LoggerFactory

import scala.collection.Iterable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

private[jobs] object OverdueTasksActor {
  def props(
    config: MarathonConf,
    taskTracker: InstanceTracker,
    reservationTimeoutHandler: TaskReservationTimeoutHandler,
    killService: TaskKillService,
    clock: Clock): Props = {
    Props(new OverdueTasksActor(new Support(config, taskTracker, reservationTimeoutHandler, killService, clock)))
  }

  /**
    * Contains the core logic for the KillOverdueTasksActor.
    */
  private class Support(
      config: MarathonConf,
      taskTracker: InstanceTracker,
      reservationTimeoutHandler: TaskReservationTimeoutHandler,
      killService: TaskKillService,
      clock: Clock) {
    import scala.concurrent.ExecutionContext.Implicits.global

    private[this] val log = LoggerFactory.getLogger(getClass)

    def check(): Future[Unit] = {
      val now = clock.now()
      log.debug("checking for overdue tasks")
      taskTracker.instancesBySpec().flatMap { instancesBySpec =>
        val instances = instancesBySpec.allInstances

        killOverdueInstances(now, instances)

        timeoutOverdueReservations(now, instances)
      }
    }

    private[this] def killOverdueInstances(now: Timestamp, instances: Iterable[Instance]): Unit = {
      overdueInstances(now, instances).foreach { overdueTask =>
        log.info("Killing overdue {}", overdueTask.id)
        killService.killTask(overdueTask, TaskKillReason.Overdue)
      }
    }

    private[this] def overdueInstances(now: Timestamp, instances: Iterable[Instance]): Iterable[Task] = {
      // stagedAt is set when the task is created by the scheduler
      val stagedExpire = now - config.taskLaunchTimeout().millis
      val unconfirmedExpire = now - config.taskLaunchConfirmTimeout().millis

      def launchedAndExpired(task: Task): Boolean = {
        task.launched.fold(false) { launched =>
          launched.status.mesosStatus.map(_.getState) match {
            case None | Some(TaskState.TASK_STARTING) if launched.status.stagedAt < unconfirmedExpire =>
              log.warn(s"Should kill: ${task.id} was launched " +
                s"${(launched.status.stagedAt.until(now).toSeconds)}s ago and was not confirmed yet"
              )
              true

            case Some(TaskState.TASK_STAGING) if launched.status.stagedAt < stagedExpire =>
              log.warn(s"Should kill: ${task.id} was staged ${(launched.status.stagedAt.until(now).toSeconds)}s" +
                s" ago and has not yet started"
              )
              true

            case _ =>
              // running
              false
          }
        }
      }

      // TODO(jdef) support pods here
      instances.collect { case t: Task => t }.filter(launchedAndExpired)
    }

    private[this] def timeoutOverdueReservations(now: Timestamp, tasks: Iterable[Instance]): Future[Unit] = {
      val taskTimeoutResults = overdueReservations(now, tasks).map { task =>
        log.warn("Scheduling ReservationTimeout for {}", task.id)
        reservationTimeoutHandler.timeout(TaskStateOp.ReservationTimeout(task.id))
      }
      Future.sequence(taskTimeoutResults).map(_ => ())
    }

    private[this] def overdueReservations(now: Timestamp, tasks: Iterable[Instance]): Iterable[Task.Reserved] = {
      Task.reservedTasks(tasks).filter { (task: Task.Reserved) =>
        task.reservation.state.timeout.exists(_.deadline <= now)
      }
    }
  }

  private[jobs] case class Check(maybeAck: Option[ActorRef])
}

private class OverdueTasksActor(support: OverdueTasksActor.Support) extends Actor with ActorLogging {
  var checkTicker: Cancellable = _

  override def preStart(): Unit = {
    import context.dispatcher
    checkTicker = context.system.scheduler.schedule(
      30.seconds, 5.seconds, self,
      OverdueTasksActor.Check(maybeAck = None)
    )
  }

  override def postStop(): Unit = {
    checkTicker.cancel()
  }

  override def receive: Receive = {
    case OverdueTasksActor.Check(maybeAck) =>
      val resultFuture = support.check()
      maybeAck match {
        case Some(ack) =>
          import akka.pattern.pipe
          import context.dispatcher
          resultFuture.pipeTo(ack)

        case None =>
          import context.dispatcher
          resultFuture.onFailure { case NonFatal(e) => log.warning("error while checking for overdue tasks", e) }
      }
  }
}
