package mesosphere.marathon
package core.task.jobs.impl

import java.time.{Clock, Instant}

import akka.actor._
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.stage._
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.ReconciliationStatusUpdate
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.Timestamp

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, blocking}

private[jobs] object OverdueTasksActor {
  def props(
    config: MarathonConf,
    instanceTracker: InstanceTracker,
    killService: KillService,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    clock: Clock): Props = {
    Props(new OverdueTasksActor(new Support(config, instanceTracker, killService, marathonSchedulerDriverHolder, clock)))
  }

  /**
    * A supporting class that implements some low-level logic and provides the delegates
    * to other components that we are using.
    *
    * We are using delegates and not direct access to the underlying components in order
    * to simplify mocking in testing.
    */
  private[jobs] class Support(
      val config: MarathonConf,
      val instanceTracker: InstanceTracker,
      val killService: KillService,
      val marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
      val clock: Clock) extends StrictLogging {
    import scala.concurrent.ExecutionContext.Implicits.global

    /**
      * Return the overdue instances for which we should perform a reconciliation and/or expunge
      *
      * @param now The current timestamp
      * @param instances The instances to check
      * @return Returns the instances that are overdue
      */
    private[impl] def filterOverdueTasks(now: Timestamp, instances: Seq[Instance]): Seq[Instance] = {
      // stagedAt is set when the task is created by the scheduler
      val unconfirmedExpire = now - config.taskLaunchConfirmTimeout().millis

      def launchedAndExpired(task: Task): Boolean = {
        task.status.condition match {
          case Condition.Created | Condition.Starting | Condition.Staging
            if task.status.stagedAt < unconfirmedExpire =>
              logger.warn(s"Should reconcile: ${task.taskId} was launched " +
                s"${task.status.stagedAt.until(now).toSeconds}s ago and was not running yet")
            true

          case _ =>
            // running
            false
        }
      }

      // TODO(PODS): adjust this to consider instance.status and `since`
      instances.filter(instance => instance.tasksMap.valuesIterator.exists(launchedAndExpired))
    }

    /**
      * Return the overdue reservations
      *
      * @param now The current timestamp
      * @param instances The instances to check
      * @return Returns the instances that are overdue
      */
    private[impl] def filterOverdueReservations(now: Timestamp, instances: Seq[Instance]): Seq[Instance] = {
      instances.filter { instance =>
        instance.isReserved && instance.reservation.exists(_.state.timeout.exists(_.deadline <= now))
      }
    }

    private[impl] def timeoutOverdueReservations(now: Timestamp, instances: Seq[Instance]): Future[Unit] = {
      val taskTimeoutResults = filterOverdueReservations(now, instances).map { instance =>
        logger.warn("Scheduling ReservationTimeout for {}", instance.instanceId)
        instanceTracker.reservationTimeout(instance.instanceId)
      }
      Future.sequence(taskTimeoutResults).map(_ => ())
    }

    private[impl] def reconcileTasksFuture(tasks: Seq[Task])(implicit blockingContext: ExecutionContext): Future[Done] = Future {
      marathonSchedulerDriverHolder.driver.map {
        d => blocking { d.reconcileTasks(tasks.flatMap(_.status.mesosStatus).asJava) }
      }
      Done
    } (blockingContext)

    // Delegate Methods

    private[impl] def now(): Timestamp = clock.now()
    private[impl] def instancesBySpec(): Future[InstanceTracker.InstancesBySpec] = instanceTracker.instancesBySpec()
    private[impl] def killInstance(instance: Instance, reason: KillReason): Future[Done] = killService.killInstance(instance, reason)

  }

  private[jobs] case class Check(maybeAck: Option[ActorRef])

  /**
    * Create the Overdue tasks graph
    *
    * @param support
    * @param checkTick
    * @param reconciliationTick
    * @param reconciliationStatusUpdates
    * @param ec
    * @param mat
    * @return
    */
  private[impl] def overdueTasksGraph(
    support: Support,
    checkTick: Source[Instant, Cancellable],
    reconciliationTick: Source[Instant, Cancellable],
    maxReconciliations: Int,
    bufferSize: Int = 100,
    reconciliationStatusUpdates: Source[ReconciliationStatusUpdate, NotUsed])(implicit ec: ExecutionContext, mat: Materializer, system: ActorSystem): Graph[ClosedShape, NotUsed] =
    GraphDSL.create() {
      implicit builder =>
        import GraphDSL.Implicits._

        //
        // [instancesBySpec] - Returns all the instances in marathon
        //
        val instancesBySpec = Flow[Instant]
          .mapAsync(1) { instant =>
            support.instancesBySpec().map(_.allInstances -> instant)
          }

        //
        // [timeoutOverdueReservations] - Times out
        //
        val timeoutOverdueReservations = Flow[(Seq[Instance], Instant)]
          .mapAsync(1){
            case (instances, instant) => support.timeoutOverdueReservations(Timestamp(instant), instances)
          }
          .to(Sink.ignore)

        //
        // [overdueInstancesFilter] - Filter out
        //
        val overdueInstancesFilter: Flow[(Seq[Instance], Instant), Instance, NotUsed] = Flow[(Seq[Instance], Instant)]
          .mapConcat { case (instances, t) => support.filterOverdueTasks(Timestamp(t), instances) }

        //
        // [reconciliationTracker] - Keeps track of reconciliation requests and ticks and prepares kill events
        //
        val reconciliationTracker = builder.add(new ReconciliationTracker(
          support.reconcileTasksFuture(_)(system.dispatchers.lookup("marathon-blocking-dispatcher")),
          bufferSize,
          maxReconciliations)
        )

        //
        // [killTasks] - Kills the tasks by their instance ID
        //
        val killTasks: Sink[Instance, NotUsed] = Flow[Instance]
          .mapAsync(1)(instance => support.killInstance(instance, KillReason.Overdue))
          .to(Sink.ignore)

        // (Utility)
        val bcast = builder.add(Broadcast[(Seq[Instance], Instant)](2))

        /////////////////////////////////////////////
        //format: OFF
                                         bcast ~> timeoutOverdueReservations
        checkTick ~> instancesBySpec ~>  bcast ~> overdueInstancesFilter ~> reconciliationTracker.in0
        reconciliationStatusUpdates            ~>                           reconciliationTracker.in1
        reconciliationTick                     ~>                           reconciliationTracker.in2

        reconciliationTracker.out ~> killTasks

        //format: ON
        /////////////////////////////////////////////

        ClosedShape
    }

}

/**
  * The Overdue Tasks Actor is started when the leader is elected and will stay active
  * for the lifetime of Marathon. This class is provided only as an adapter for the
  * existing `leadershipModule.startWhenLeader` interface.
  *
  * @param support The supporting object that contains utility functions and instance references
  */
private class OverdueTasksActor(support: OverdueTasksActor.Support) extends Actor with StrictLogging {
  implicit val mat = ActorMaterializer()
  import context.{dispatcher, system}

  private val unconfirmedTaskReconciliationTimeout = support.config.unconfirmedTaskReconciliationTimeout().millis
  private val unconfirmedTaskReconciliationInterval = support.config.unconfirmedTaskReconciliationInterval().millis
  private val maxReconciliations = Math.toIntExact((unconfirmedTaskReconciliationTimeout / unconfirmedTaskReconciliationInterval).round)

  val checkInstancesTick: Source[Instant, Cancellable] = Source.tick(30.seconds, 30.seconds, tick = Instant.now())
  val performReconciliationTick: Source[Instant, Cancellable] = Source.tick(
    unconfirmedTaskReconciliationInterval,
    unconfirmedTaskReconciliationInterval,
    Instant.now()
  )

  // Forward `ReconciliationStatusUpdate` from the eventStream to the graph
  private[this] val actorSource = Source.actorRef[ReconciliationStatusUpdate](1000, OverflowStrategy.dropNew)
  private[this] val (actorListener, reconciliationStatusUpdates) = actorSource.preMaterialize()

  // Create runnable graph
  private[this] val materializedOverdueTasksGraph: RunnableGraph[NotUsed] = RunnableGraph.fromGraph(
    OverdueTasksActor.overdueTasksGraph(
      support,
      checkInstancesTick,
      performReconciliationTick,
      maxReconciliations,
      100,
      reconciliationStatusUpdates
    )
  )

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(actorListener, classOf[ReconciliationStatusUpdate])
    materializedOverdueTasksGraph.run()
  }

  override def receive: Receive = {
    case msg => logger.error(s"unexpected message $msg arrived on OverdueTasksActor")
  }
}

/**
  * Keeps track of the number of reconciliation attempts on an instance
  *
  * @param instance
  * @param attempts
  */
case class ReconciliationState(
    instance: Instance,
    attempts: Int = 0
)

/**
  * The core logic of the reconciliation tasks operation
  *
  * Inlets:
  *  0: Inlet[Instance] - Stream of candidate instances to be checked
  *  1: Inlet[ReconciliationStatusUpdate] - Stream of reconciliation updates from mesos
  *  2: Inlet[Instant] - Stream ot tick events that trigger the reconciliation attempts
  *
  * Outlets:
  *  0: Outlet[Instance] - The instances that should be killd
  *
  * @param reconcileTasks
  * @param bufferSize
  * @param maxReconciliations
  */
class ReconciliationTracker(
    reconcileTasks: Seq[Task] => Future[Done],
    bufferSize: Int,
    maxReconciliations: Int,
) extends GraphStage[FanInShape3[Instance, ReconciliationStatusUpdate, Instant, Instance]] {
//todo metrics and logs
  val instanceIn: Inlet[Instance] = Inlet[Instance]("ReconciliationTracker.instanceIn")
  val statusUpdateIn: Inlet[ReconciliationStatusUpdate] = Inlet[ReconciliationStatusUpdate]("ReconciliationTracker.statusUpdateIn")
  val tickIn: Inlet[Instant] = Inlet[Instant]("ReconciliationTracker.tickIn")
  val out: Outlet[Instance] = Outlet[Instance]("ReconciliationTracker.out")

  override def shape: FanInShape3[Instance, ReconciliationStatusUpdate, Instant, Instance] = new FanInShape3(instanceIn, statusUpdateIn, tickIn, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var pendingReconciliations: Map[Instance.Id, ReconciliationState] = Map.empty
      var downstreamWaiting = false

      def bufferFull: Boolean = pendingReconciliations.size > bufferSize

      override def preStart(): Unit = {
        pull(instanceIn)
        pull(statusUpdateIn)
        pull(tickIn)
      }

      setHandler(instanceIn, new InHandler {
        override def onPush(): Unit = {
          val instance = grab(instanceIn)
          if (!pendingReconciliations.contains(instance.instanceId)) {
            pendingReconciliations += ((instance.instanceId, ReconciliationState(instance)))
            if (!bufferFull) {
              pull(instanceIn) //request new element if the buffer is not full
            }
          } else {
            pull(instanceIn)
          }
        }
      })

      setHandler(statusUpdateIn, new InHandler {
        override def onPush(): Unit = {
          val ReconciliationStatusUpdate(taskId, taskStatus) = grab(statusUpdateIn)
          taskStatus match {
            case Condition.Starting =>
              // Nothing has started yet, so we keep waiting.

            case Condition.Staging =>
              // The task could be stucked in Staging foerever (ex. if Docker is unresponsive)
              // during that time we don't want to expunge the task. Therefore we are reseting
              // the reconciliation counter to zero.

              val maybePendingInstance = pendingReconciliations.valuesIterator.find(_.instance.tasksMap.contains(taskId))
              maybePendingInstance.foreach { state   =>
                pendingReconciliations += state.instance.instanceId -> state.copy(attempts = 0)
              }

            case _ => //Status changed, let's remove the instance from the tracker
              val maybeInstanceId = pendingReconciliations.valuesIterator.find(_.instance.tasksMap.contains(taskId)).map(_.instance.instanceId)
              maybeInstanceId.foreach { instanceId =>
                pendingReconciliations -= instanceId
              }
          }
          pull(statusUpdateIn)
        }
      })

      setHandler(tickIn, new InHandler {
        override def onPush(): Unit = {
          val reconcileTasksBuilder = List.newBuilder[Task]

          pendingReconciliations = pendingReconciliations.mapValues {
            // If we have not reached the maximum attempts, schedule reconciliation
            case ReconciliationState(instance, count) if count < maxReconciliations =>
              reconcileTasksBuilder ++= instance.tasksMap.values
              ReconciliationState(instance, count + 1)

            // Otherwise schedule expunge
            case inst: ReconciliationState if inst.attempts >= maxReconciliations =>
              if (downstreamWaiting) {
                downstreamWaiting = false
                pendingReconciliations -= inst.instance.instanceId
                push(out, inst.instance)
                null
              } else {
                inst
              }

          }.filter(_ != null)

          val tasksStatuses = reconcileTasksBuilder.result()
          if (tasksStatuses.nonEmpty) {
            reconcileTasks(tasksStatuses)
          }

          pull(tickIn)
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (pendingReconciliations.nonEmpty) {
            val toEmit = pendingReconciliations.valuesIterator
              .filter(_.attempts >= maxReconciliations)
              .map(_.instance)
              .toList
            val stopTracking = toEmit.map(_.instanceId).toSet
            pendingReconciliations = pendingReconciliations.filterKeys(instanceId => !stopTracking.contains(instanceId))
            emitMultiple(out, toEmit)
          } else {
            downstreamWaiting = true
            if (!hasBeenPulled(instanceIn)){
              pull(instanceIn)
            }
          }
        }
      })

    }

}