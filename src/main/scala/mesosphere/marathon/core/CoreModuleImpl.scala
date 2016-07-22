package mesosphere.marathon.core

import javax.inject.Named

import akka.actor.ActorSystem
import akka.event.EventStream
import com.google.inject.{ Inject, Provider }
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.auth.AuthModule
import mesosphere.marathon.core.base.{ ActorsModule, Clock, ShutdownHooks }
import mesosphere.marathon.core.election._
import mesosphere.marathon.core.event.{ EventModule, EventSubscribers }
import mesosphere.marathon.core.flow.FlowModule
import mesosphere.marathon.core.history.HistoryModule
import mesosphere.marathon.core.launcher.LauncherModule
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.base.util.StopOnFirstMatchingOfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerModule
import mesosphere.marathon.core.matcher.reconcile.OfferMatcherReconciliationModule
import mesosphere.marathon.core.plugin.PluginModule
import mesosphere.marathon.core.readiness.ReadinessModule
import mesosphere.marathon.core.storage.StorageModule
import mesosphere.marathon.core.storage.repository.impl.legacy.store.EntityStore
import mesosphere.marathon.core.task.bus.TaskBusModule
import mesosphere.marathon.core.task.jobs.TaskJobsModule
import mesosphere.marathon.core.task.tracker.TaskTrackerModule
import mesosphere.marathon.core.task.update.{ TaskStatusUpdateProcessor, TaskUpdateStep }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerDriverHolder, ModuleNames }

import scala.concurrent.ExecutionContext
import scala.util.Random

/**
  * Provides the wiring for the core module.
  *
  * Its parameters represent guice wired dependencies.
  * [[CoreGuiceModule]] exports some dependencies back to guice.
  */
class CoreModuleImpl @Inject() (
  // external dependencies still wired by guice
  marathonConf: MarathonConf,
  eventStream: EventStream,
  httpConf: HttpConf,
  @Named(ModuleNames.HOST_PORT) hostPort: String,
  metrics: Metrics,
  actorSystem: ActorSystem,
  marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
  taskStatusUpdateProcessor: Provider[TaskStatusUpdateProcessor],
  clock: Clock,
  taskStatusUpdateSteps: Seq[TaskUpdateStep],
  @Named(ModuleNames.STORE_EVENT_SUBSCRIBERS) eventSubscribersStore: EntityStore[EventSubscribers])
    extends CoreModule {

  // INFRASTRUCTURE LAYER

  private[this] lazy val random = Random
  private[this] lazy val shutdownHookModule = ShutdownHooks()
  override lazy val actorsModule = new ActorsModule(shutdownHookModule, actorSystem)

  override lazy val leadershipModule = LeadershipModule(actorsModule.actorRefFactory, electionModule.service)
  override lazy val electionModule = new ElectionModule(
    marathonConf,
    actorSystem,
    eventStream,
    httpConf,
    metrics,
    hostPort,
    shutdownHookModule
  )

  // TASKS

  override lazy val taskBusModule = new TaskBusModule()
  override lazy val taskTrackerModule =
    new TaskTrackerModule(clock, metrics, marathonConf, leadershipModule,
      storageModule.taskRepository, taskStatusUpdateSteps)(actorsModule.materializer)
  override lazy val taskJobsModule = new TaskJobsModule(marathonConf, leadershipModule, clock)
  override lazy val storageModule = StorageModule(
    marathonConf)(
    metrics,
    actorsModule.materializer,
    ExecutionContext.global,
    actorSystem.scheduler,
    actorSystem)

  // READINESS CHECKS
  lazy val readinessModule = new ReadinessModule(actorSystem)

  // OFFER MATCHING AND LAUNCHING TASKS

  private[this] lazy val offerMatcherManagerModule = new OfferMatcherManagerModule(
    // infrastructure
    clock, random, metrics, marathonConf,
    leadershipModule
  )

  private[this] lazy val offerMatcherReconcilerModule =
    new OfferMatcherReconciliationModule(
      marathonConf,
      clock,
      actorSystem.eventStream,
      taskTrackerModule.taskTracker,
      storageModule.groupRepository,
      offerMatcherManagerModule.subOfferMatcherManager,
      leadershipModule
    )

  override lazy val launcherModule = new LauncherModule(
    // infrastructure
    clock, metrics, marathonConf,

    // external guicedependencies
    taskTrackerModule.taskCreationHandler,
    marathonSchedulerDriverHolder,

    // internal core dependencies
    StopOnFirstMatchingOfferMatcher(
      offerMatcherReconcilerModule.offerMatcherReconciler,
      offerMatcherManagerModule.globalOfferMatcher
    ),
    pluginModule.pluginManager
  )

  override lazy val appOfferMatcherModule = new LaunchQueueModule(
    marathonConf,
    leadershipModule, clock,

    // internal core dependencies
    offerMatcherManagerModule.subOfferMatcherManager,
    maybeOfferReviver,

    // external guice dependencies
    taskTrackerModule.taskTracker,
    launcherModule.taskOpFactory
  )

  // PLUGINS

  override lazy val pluginModule = new PluginModule(marathonConf)

  override lazy val authModule: AuthModule = new AuthModule(pluginModule.pluginManager)

  // FLOW CONTROL GLUE

  private[this] lazy val flowActors = new FlowModule(leadershipModule)

  flowActors.refillOfferMatcherManagerLaunchTokens(
    marathonConf, taskBusModule.taskStatusObservables, offerMatcherManagerModule.subOfferMatcherManager)

  /** Combine offersWanted state from multiple sources. */
  private[this] lazy val offersWanted =
    offerMatcherManagerModule.globalOfferMatcherWantsOffers
      .combineLatest(offerMatcherReconcilerModule.offersWantedObservable)
      .map { case (managerWantsOffers, reconciliationWantsOffers) => managerWantsOffers || reconciliationWantsOffers }

  lazy val maybeOfferReviver = flowActors.maybeOfferReviver(
    clock, marathonConf,
    actorSystem.eventStream,
    offersWanted,
    marathonSchedulerDriverHolder)

  // EVENT

  override lazy val eventModule: EventModule = new EventModule(
    eventStream, actorSystem, marathonConf, metrics, clock, eventSubscribersStore, electionModule.service,
    authModule.authenticator, authModule.authorizer)

  // HISTORY

  override lazy val historyModule: HistoryModule =
    new HistoryModule(eventStream, actorSystem, storageModule.taskFailureRepository)

  // GREEDY INSTANTIATION
  //
  // Greedily instantiate everything.
  //
  // lazy val allows us to write down object instantiations in any order.
  //
  // The LeadershipModule requires that all actors have been registered when the controller
  // is created. Changing the wiring order for this feels wrong since it is nicer if it
  // follows architectural logic. Therefore we instantiate them here explicitly.

  taskJobsModule.handleOverdueTasks(
    taskTrackerModule.taskTracker,
    taskTrackerModule.taskReservationTimeoutHandler,
    marathonSchedulerDriverHolder
  )
  taskJobsModule.expungeOverdueLostTasks(taskTrackerModule.taskTracker, taskTrackerModule.stateOpProcessor)
  maybeOfferReviver
  offerMatcherManagerModule
  launcherModule
  offerMatcherReconcilerModule.start()
  eventModule
  historyModule
}
