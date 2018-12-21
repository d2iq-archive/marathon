package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor._
import akka.event.EventStream
import akka.pattern._
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec

import scala.async.Async.{async, await}
import scala.collection.{SortedSet, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

class TaskReplaceActor(
    val deploymentManagerActor: ActorRef,
    val status: DeploymentStatus,
    val killService: KillService,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    promise: Promise[Unit]) extends Actor with Stash with ReadinessBehavior with StrictLogging {
  import TaskReplaceActor._

  // All existing instances of this app independent of the goal.
  //
  // Killed resident tasks are not expunged from the instances list. Ignore
  // them. LaunchQueue takes care of launching instances against reservations
  // first
  val currentInstances = instanceTracker.specInstancesSync(runSpec.id)

  // In case previous master was abdicated while the deployment was still running we might have
  // already started some new tasks.
  // All already started and active tasks are filtered while the rest is considered
  private[this] val (instancesAlreadyStarted, oldInstances) = {
    currentInstances.partition(_.runSpecVersion == runSpec.version)
  }

  // Old and new instances that have the Goal.Running
  val activeInstances = currentInstances.filter(_.state.goal == Goal.Running)

  // The ignition strategy for this run specification
  private[this] val ignitionStrategy = computeRestartStrategy(runSpec, activeInstances.size)

  // compute all variables maintained in this actor =========================================================

  // Only old instances that still have the Goal.Running
  val oldActiveInstances = oldInstances.filter(_.state.goal == Goal.Running)

  // All instances to kill as set for quick lookup
  private[this] var oldInstanceIds: SortedSet[Id] = oldActiveInstances.map(_.instanceId).to[SortedSet]

  // All instances to kill queued up
  private[this] val toKill: mutable.Queue[Instance.Id] = oldActiveInstances.map(_.instanceId).to[mutable.Queue]

  // The number of started instances. Defaults to the number of already started instances.
  var instancesStarted: Int = instancesAlreadyStarted.size

  @SuppressWarnings(Array("all")) // async/await
  override def preStart(): Unit = {
    super.preStart()
    // subscribe to all needed events
    eventBus.subscribe(self, classOf[InstanceChanged])
    eventBus.subscribe(self, classOf[InstanceHealthChanged])

    // reconcile the state from a possible previous run
    reconcileAlreadyStartedInstances()

    // kill old instances to free some capacity
    for (_ <- 0 until ignitionStrategy.nrToKillImmediately) killNextOldInstance()

    // start new instances, if possible
    launchInstances().pipeTo(self)

    // reset the launch queue delay
    logger.info("Resetting the backoff delay before restarting the runSpec")
    launchQueue.resetDelay(runSpec)

    // it might be possible, that we come here, but nothing is left to do
    checkFinished()
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  override def receive: Receive = initializing

  private def initializing: Receive = {
    case Done =>
      context.become(initialized)
      unstashAll()

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("while loading tasks", cause)

    case stashMe: AnyRef =>
      stash()
  }

  private def initialized: Receive = readinessBehavior orElse replaceBehavior

  /**
    * This actor is a bad example on how future orchestrator might handle the instances. The logic below handles instance
    * changed across three dimensions:
    *
    * a) old vs. new - instances with old RunSpec version are gradually replaced with the new one
    * b) goals - it's not enough to check instance condition e.g. if the new instance task FAILED but the goal is
    *            Goal.Running then it will be automatically rescheduled by the TaskLauncherActor
    * c) condition - we additionally check whether or not the instance is considered terminal/active
    *
    * What makes it so hard to work with, is the fact, that it basically counts old and new instances and the additional
    * dimensions are expressed through filters on the [[InstanceChanged]] events. It can be a more robust state-machine
    * which ideally has a set of new and old instances which then decommissions old ones and schedules new ones, never
    * incrementing/decrementing counters and never over/under scales.
    *
    */
  def replaceBehavior: Receive = {

    // === An InstanceChanged event for the *new* instance ===
    case ic: InstanceChanged if !oldInstanceIds(ic.id) =>
      val id = ic.id
      val condition = ic.condition
      val instance = ic.instance
      val goal = instance.state.goal
      val agentId = instance.agentInfo.fold(Option.empty[String])(_.agentId)

      // 1) Did the new instance task fail?
      if (considerTerminal(condition) && goal == Goal.Running) {
        logger.warn(s"New $id is terminal ($condition) on agent $agentId during app $pathId restart: $condition reservation: ${instance.reservation}. Waiting for the task to restart...")
        instanceTerminated(id)
        instancesStarted -= 1
      } // 2) Did someone tamper with new instance's goal? Don't do that - there should be only one "orchestrator" per service per time!
      else if (considerTerminal(condition) && goal != Goal.Running) {
        logger.error(s"New $id is terminal ($condition) on agent $agentId during app $pathId restart (reservation: ${instance.reservation}) and the goal ($goal) is *NOT* Running! This means that someone is interfering with current deployment!")
      } else {
        logger.info(s"Unhandled InstanceChanged event for new instanceId=$id, considered terminal=${considerTerminal(condition)} and current goal=${instance.state.goal}")
      }

    // === An InstanceChanged event for the *old* instance ===
    case ic: InstanceChanged if oldInstanceIds(ic.id) =>
      val id = ic.id
      val condition = ic.condition
      val instance = ic.instance
      val goal = instance.state.goal
      val agentId = instance.agentInfo.fold(Option.empty[String])(_.agentId)

      // 1) An old instance terminated out of band and was not yet chosen to be decommissioned or stopped.
      // We stop/decommission the instance and let it be rescheduled with new instance RunSpec
      if (considerTerminal(condition) && goal == Goal.Running) {
        logger.info(s"Old instance $id became $condition during an upgrade but still has goal Running. We will decommission that instance and launch new one with the new RunSpec.")
        oldInstanceIds -= id
        instanceTerminated(id)
        val goal = if (runSpec.isResident) Goal.Stopped else Goal.Decommissioned
        instanceTracker.setGoal(instance.instanceId, goal)
          .flatMap(_ => killService.killInstance(instance, KillReason.Upgrading))
          .pipeTo(self)
      } // 2) An old and decommissioned instance was successfully killed
      else if (considerTerminal(condition) && instance.state.goal != Goal.Running) {
        logger.info(s"Old $id became $condition. Launching more instances.")
        oldInstanceIds -= id
        instanceTerminated(id)
        launchInstances()
          .map(_ => CheckFinished)
          .pipeTo(self)
      } else {
        logger.info(s"Unhandled InstanceChanged event for an old instanceId=$id, considered terminal=${considerTerminal(condition)} and goal=${instance.state.goal}")
      }

    case Status.Failure(e) =>
      // This is the result of failed launchQueue.addAsync(...) call. Log the message and
      // restart this actor. Next reincarnation should try to start from the beginning.
      logger.warn("Failed to launch instances: ", e)
      throw e

    case Done => // This is the result of successful launchQueue.addAsync(...) call. Nothing to do here

    case CheckFinished => checkFinished()

  }

  override def instanceConditionChanged(instanceId: Instance.Id): Unit = {
    if (healthyInstances(instanceId) && readyInstances(instanceId)) killNextOldInstance(Some(instanceId))
    checkFinished()
  }

  def reconcileAlreadyStartedInstances(): Unit = {
    logger.info(s"Reconciling instances during ${runSpec.id} deployment: found ${instancesAlreadyStarted.size} already started instances " +
      s"and ${oldInstanceIds.size} old instances: ${if (currentInstances.size > 0) currentInstances.map{ i => i.instanceId -> i.state.condition } else "[]"}")
    instancesAlreadyStarted.foreach(reconcileHealthAndReadinessCheck)
  }

  // Careful not to make this method completely asynchronous - it changes local actor's state `instancesStarted`.
  // Only launching new instances needs to be asynchronous.
  def launchInstances(): Future[Done] = {
    val leftCapacity = math.max(0, ignitionStrategy.maxCapacity - oldInstanceIds.size - instancesStarted)
    val instancesNotStartedYet = math.max(0, runSpec.instances - instancesStarted)
    val instancesToStartNow = math.min(instancesNotStartedYet, leftCapacity)
    if (instancesToStartNow > 0) {
      logger.info(s"Restarting app $pathId: queuing $instancesToStartNow new instances")
      instancesStarted += instancesToStartNow
      launchQueue.add(runSpec, instancesToStartNow)
    } else {
      logger.info(s"Restarting app $pathId. No need to start new instances right now with leftCapacity = $leftCapacity, instancesNotStartedYet = $instancesNotStartedYet and instancesToStartNow = $instancesToStartNow")
      Future.successful(Done)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  def killNextOldInstance(maybeNewInstanceId: Option[Instance.Id] = None): Unit = {
    if (toKill.nonEmpty) {
      val dequeued = toKill.dequeue()
      async {
        await(instanceTracker.get(dequeued)) match {
          case None =>
            logger.warn(s"Was about to kill instance $dequeued but it did not exist in the instance tracker anymore.")
          case Some(nextOldInstance) =>
            maybeNewInstanceId match {
              case Some(newInstanceId: Instance.Id) =>
                logger.info(s"Killing old ${nextOldInstance.instanceId} because $newInstanceId became reachable")
              case _ =>
                logger.info(s"Killing old ${nextOldInstance.instanceId}")
            }

            if (runSpec.isResident) {
              await(instanceTracker.setGoal(nextOldInstance.instanceId, Goal.Stopped))
            } else {
              await(instanceTracker.setGoal(nextOldInstance.instanceId, Goal.Decommissioned))
            }
            await(killService.killInstance(nextOldInstance, KillReason.Upgrading))
        }
      }
    }
  }

  def checkFinished(): Unit = {
    if (targetCountReached(runSpec.instances) && oldInstanceIds.isEmpty) {
      logger.info(s"All new instances for $pathId are ready and all old instances have been killed")
      promise.trySuccess(())
      context.stop(self)
    } else {
      logger.info(s"For run spec: [${runSpec.id}] there are [${healthyInstances.size}] healthy and " +
        s"[${readyInstances.size}] ready new instances and " +
        s"[${oldInstanceIds.size}] old instances (${oldInstanceIds.take(3)}). Target count is ${runSpec.instances}.")
    }
  }
}

object TaskReplaceActor extends StrictLogging {

  object CheckFinished

  //scalastyle:off
  def props(
    deploymentManagerActor: ActorRef,
    status: DeploymentStatus,
    killService: KillService,
    launchQueue: LaunchQueue,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    app: RunSpec,
    promise: Promise[Unit]): Props = Props(
    new TaskReplaceActor(deploymentManagerActor, status, killService, launchQueue, instanceTracker, eventBus,
      readinessCheckExecutor, app, promise)
  )

  /** Encapsulates the logic how to get a Restart going */
  private[impl] case class RestartStrategy(nrToKillImmediately: Int, maxCapacity: Int)

  private[impl] def computeRestartStrategy(runSpec: RunSpec, runningInstancesCount: Int): RestartStrategy = {
    // in addition to a spec which passed validation, we require:
    require(runSpec.instances > 0, s"instances must be > 0 but is ${runSpec.instances}")
    require(runningInstancesCount >= 0, s"running instances count must be >=0 but is $runningInstancesCount")

    val minHealthy = (runSpec.instances * runSpec.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    var maxCapacity = (runSpec.instances * (1 + runSpec.upgradeStrategy.maximumOverCapacity)).toInt
    var nrToKillImmediately = math.max(0, runningInstancesCount - minHealthy)

    if (minHealthy == maxCapacity && maxCapacity <= runningInstancesCount) {
      if (runSpec.isResident) {
        // Kill enough instances so that we end up with one instance below minHealthy.
        // TODO: We need to do this also while restarting, since the kill could get lost.
        nrToKillImmediately = runningInstancesCount - minHealthy + 1
        logger.info(
          "maxCapacity == minHealthy for resident app: " +
            s"adjusting nrToKillImmediately to $nrToKillImmediately in order to prevent over-capacity for resident app"
        )
      } else {
        logger.info("maxCapacity == minHealthy: Allow temporary over-capacity of one instance to allow restarting")
        maxCapacity += 1
      }
    }

    logger.info(s"For minimumHealthCapacity ${runSpec.upgradeStrategy.minimumHealthCapacity} of ${runSpec.id.toString} leave " +
      s"$minHealthy instances running, maximum capacity $maxCapacity, killing $nrToKillImmediately of " +
      s"$runningInstancesCount running instances immediately. (RunSpec version ${runSpec.version})")

    assume(nrToKillImmediately >= 0, s"nrToKillImmediately must be >=0 but is $nrToKillImmediately")
    assume(maxCapacity > 0, s"maxCapacity must be >0 but is $maxCapacity")
    def canStartNewInstances: Boolean = minHealthy < maxCapacity || runningInstancesCount - nrToKillImmediately < maxCapacity
    assume(canStartNewInstances, "must be able to start new instances")

    RestartStrategy(nrToKillImmediately = nrToKillImmediately, maxCapacity = maxCapacity)
  }
}

