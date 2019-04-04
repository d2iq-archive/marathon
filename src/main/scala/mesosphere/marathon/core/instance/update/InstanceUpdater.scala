package mesosphere.marathon
package core.instance.update

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance, Reservation}
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.{MesosUpdate, Reserve}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.update.TaskUpdateEffect
import mesosphere.marathon.state.{Timestamp, UnreachableEnabled}
import org.apache.mesos.{Protos => MesosProtos}

/**
  * Provides methods that apply a given [[InstanceUpdateOperation]]
  */
object InstanceUpdater extends StrictLogging {
  private[this] val eventsGenerator = InstanceChangedEventsGenerator

  /**
    * Apply the provided task update to the instance. If the instance is resident, and the task is terminal, update the reservation to be suspended.
    *
    * @param instance
    * @param updatedTask
    * @param now
    * @return
    */
  private[instance] def applyTaskUpdate(instance: Instance, updatedTask: Task, now: Timestamp): Instance = {
    val updatedTasks = instance.tasksMap.updated(updatedTask.taskId, updatedTask)

    // We need to suspend reservation on already launched reserved instances
    // to prevent reservations being destroyed/unreserved.
    val updatedReservation = if (updatedTask.status.condition.isTerminal && instance.hasReservation && !instance.reservation.exists(r => r.state.isInstanceOf[Reservation.State.Suspended])) {
      val suspendedState = Reservation.State.Suspended(timeout = None)
      instance.reservation.map(_.copy(state = suspendedState))
    } else {
      instance.reservation
    }

    instance.copy(
      tasksMap = updatedTasks,
      state = Instance.InstanceState(Some(instance.state), updatedTasks, now, instance.unreachableStrategy, instance.state.goal),
      reservation = updatedReservation)
  }

  private[marathon] def reserve(instance: Instance, op: Reserve, now: Timestamp): InstanceUpdateEffect = {
    val updatedInstance = instance.reserved(op.reservation, op.agentInfo)
    val events = eventsGenerator.events(updatedInstance, task = None, now, previousState = None)
    InstanceUpdateEffect.Update(updatedInstance, oldState = None, events)
  }

  private def shouldBeExpunged(instance: Instance): Boolean =
    instance.tasksMap.values.forall(t => t.isTerminal) && instance.state.goal == Goal.Decommissioned

  private def shouldAbandonReservation(instance: Instance): Boolean = {

    def allAreTerminal = instance.tasksMap.values.iterator.forall { task =>
      task.status.condition.isTerminal
    }

    def anyAreGoneByOperator = instance.tasksMap.values.iterator
      .flatMap(_.status.mesosStatus)
      .exists { status =>
        status.getState == MesosProtos.TaskState.TASK_GONE_BY_OPERATOR
      }

    instance.reservation.nonEmpty && anyAreGoneByOperator && allAreTerminal
  }

  private[marathon] def mesosUpdate(instance: Instance, op: MesosUpdate): InstanceUpdateEffect = {
    val now = op.now
    val taskId = Task.Id.parse(op.mesosStatus.getTaskId)
    instance.tasksMap.get(taskId).map { task =>
      val taskEffect = task.update(instance, op.condition, op.mesosStatus, now)
      taskEffect match {
        case TaskUpdateEffect.Update(updatedTask) =>
          val updated: Instance = applyTaskUpdate(instance, updatedTask, now)
          val events = eventsGenerator.events(updated, Some(updatedTask), now, previousState = Some(instance.state))
          if (shouldBeExpunged(updated)) {
            logger.info("Requesting to expunge {}, all tasks are terminal, instance has no reservation and is not Stopped", updated.instanceId)
            InstanceUpdateEffect.Expunge(updated, events)
          } else if (shouldAbandonReservation(updated)) {
            val withoutReservation = updated.copy(agentInfo = None, reservation = None, state = updated.state.copy(condition = Condition.Scheduled))
            InstanceUpdateEffect.Update(withoutReservation, oldState = Some(instance), events)
          } else {
            InstanceUpdateEffect.Update(updated, oldState = Some(instance), events)
          }

        // We might still become UnreachableInactive.
        case TaskUpdateEffect.Noop if op.condition == Condition.Unreachable &&
          instance.state.condition != Condition.UnreachableInactive =>
          val updated: Instance = applyTaskUpdate(instance, task, now)
          if (updated.state.condition == Condition.UnreachableInactive) {
            updated.unreachableStrategy match {
              case u: UnreachableEnabled =>
                logger.info(
                  s"${updated.instanceId} is updated to UnreachableInactive after being Unreachable for more than ${u.inactiveAfter.toSeconds} seconds.")
              case _ =>
                // We shouldn't get here
                logger.error(
                  s"${updated.instanceId} is updated to UnreachableInactive in spite of there being no UnreachableStrategy")

            }
            val events = eventsGenerator.events(
              updated, Some(task), now, previousState = Some(instance.state))
            InstanceUpdateEffect.Update(updated, oldState = Some(instance), events)
          } else {
            InstanceUpdateEffect.Noop(instance.instanceId)
          }

        case TaskUpdateEffect.Noop =>
          InstanceUpdateEffect.Noop(instance.instanceId)

        case TaskUpdateEffect.Failure(cause) =>
          InstanceUpdateEffect.Failure(cause)
      }
    }.getOrElse(InstanceUpdateEffect.Failure(s"$taskId not found in ${instance.instanceId}: ${instance.tasksMap.keySet}"))
  }

  private[marathon] def reservationTimeout(instance: Instance, now: Timestamp): InstanceUpdateEffect = {
    if (instance.hasReservation) {
      // TODO(cleanup): Using Killed for now; we have no specific state yet bit this must be considered Terminal
      val updatedInstance = instance.copy(
        state = instance.state.copy(condition = Condition.Killed)
      )
      val events = eventsGenerator.events(updatedInstance, task = None, now, previousState = Some(instance.state))

      logger.debug(s"Expunge reserved ${instance.instanceId}")

      InstanceUpdateEffect.Expunge(instance, events)
    } else {
      InstanceUpdateEffect.Failure("ReservationTimeout can only be applied to a reserved instance")
    }
  }

  private[marathon] def forceExpunge(instance: Instance, now: Timestamp): InstanceUpdateEffect = {
    val updatedInstance = instance.copy(
      // TODO(cleanup): Using Killed for now; we have no specific state yet bit this must be considered Terminal
      state = instance.state.copy(condition = Condition.Killed)
    )
    val events = eventsGenerator.events(
      updatedInstance, task = None, now, previousState = Some(instance.state))

    logger.debug(s"Force expunge ${instance.instanceId}")

    InstanceUpdateEffect.Expunge(updatedInstance, events)
  }

  private[marathon] def revert(instance: Instance): InstanceUpdateEffect = {
    InstanceUpdateEffect.Update(instance, oldState = None, events = Nil)
  }

  private[marathon] def changeGoal(instance: Instance, op: InstanceUpdateOperation.ChangeGoal, now: Timestamp): InstanceUpdateEffect = {
    val updatedInstance = instance.copy(state = instance.state.copy(goal = op.goal))
    val events = eventsGenerator.events(updatedInstance, task = None, now, previousState = Some(instance.state))

    if (InstanceUpdater.shouldBeExpunged(updatedInstance)) {
      logger.info(s"Instance ${instance.instanceId} with current condition ${instance.state.condition} has it's goal updated from ${instance.state.goal} to ${op.goal}. Because of that instance should be expunged now.")
      InstanceUpdateEffect.Expunge(updatedInstance, events = events)
    } else {
      logger.info(s"Instance ${instance.instanceId} with current condition ${instance.state.condition} has it's goal updated from ${instance.state.goal} to ${op.goal}.")
      InstanceUpdateEffect.Update(updatedInstance, oldState = Some(instance), events = events)
    }
  }
}
