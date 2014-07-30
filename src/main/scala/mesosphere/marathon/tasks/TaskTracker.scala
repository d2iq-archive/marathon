package mesosphere.marathon.tasks

import java.io._
import javax.inject.Inject

import mesosphere.marathon.Protos._
import mesosphere.marathon.state.PathId
import mesosphere.marathon.{ Main, MarathonConf }
import org.apache.log4j.Logger
import org.apache.mesos.Protos.TaskStatus
import org.apache.mesos.state.{ State, Variable }

import scala.collection.JavaConverters._
import scala.collection._
import scala.concurrent.Future

class TaskTracker @Inject() (state: State, config: MarathonConf) {

  import mesosphere.marathon.tasks.TaskTracker.App
  import mesosphere.util.BackToTheFuture.futureToFuture
  import mesosphere.util.ThreadPoolContext.context

  implicit val timeout = config.zkFutureTimeout

  private[this] val log = Logger.getLogger(getClass.getName)

  val PREFIX = "task:"
  val ID_DELIMITER = ":"

  private[this] val apps = new mutable.HashMap[PathId, App] with mutable.SynchronizedMap[PathId, App]

  private[tasks] def fetchFromState(key: String) = state.fetch(key).get()

  private[tasks] def getKey(appId: PathId, taskId: String): String = {
    PREFIX + appId.toString + ID_DELIMITER + taskId
  }

  def get(appId: PathId): mutable.Set[MarathonTask] =
    apps.getOrElseUpdate(appId, fetchApp(appId)).tasks

  def list: mutable.HashMap[PathId, App] = apps

  def count(appId: PathId): Int = get(appId).size

  def contains(appId: PathId): Boolean = apps.contains(appId)

  def take(appId: PathId, n: Int): Set[MarathonTask] = get(appId).take(n)

  def created(appId: PathId, task: MarathonTask): Unit = {
    // Keep this here so running() can pick it up
    get(appId) += task
  }

  def running(appId: PathId, status: TaskStatus): Future[MarathonTask] = {
    val taskId = status.getTaskId.getValue
    val task = get(appId).find(_.getId == taskId) match {
      case Some(stagedTask) =>
        get(appId).remove(stagedTask)
        stagedTask.toBuilder
          .setStartedAt(System.currentTimeMillis)
          .addStatuses(status)
          .build

      case _ =>
        log.warn(s"No staged task for ID $taskId")
        // We lost track of the host and port of this task, but still need to keep track of it
        MarathonTask.newBuilder
          .setId(taskId)
          .setStagedAt(System.currentTimeMillis)
          .setStartedAt(System.currentTimeMillis)
          .addStatuses(status)
          .build
    }
    get(appId) += task
    store(appId, task).map(_ => task)
  }

  def terminated(appId: PathId, status: TaskStatus): Future[Option[MarathonTask]] = {
    val appTasks = get(appId)
    val app = apps(appId)
    val taskId = status.getTaskId.getValue

    appTasks.find(_.getId == taskId) match {
      case Some(task) =>
        app.tasks = appTasks - task

        val variable = fetchFromState(getKey(appId, taskId))
        state.expunge(variable)

        log.info(s"Task ${taskId} expunged and removed from TaskTracker")

        if (app.shutdown && app.tasks.isEmpty) {
          // Are we shutting down this app? If so, remove it
          remove(appId)
        }

        Future.successful(Some(task))
      case None =>
        if (app.shutdown && app.tasks.isEmpty) {
          // Are we shutting down this app? If so, remove it
          remove(appId)
        }
        Future.successful(None)
    }
  }

  def shutdown(appId: PathId): Unit = {
    apps.getOrElseUpdate(appId, fetchApp(appId)).shutdown = true
    if (apps(appId).tasks.isEmpty) remove(appId)
  }

  private[this] def remove(appId: PathId) {
    apps.remove(appId)
    log.warn(s"App ${appId} removed from TaskTracker")
  }

  def statusUpdate(appId: PathId, status: TaskStatus): Future[Option[MarathonTask]] = {
    val taskId = status.getTaskId.getValue
    get(appId).find(_.getId == taskId) match {
      case Some(task) =>
        get(appId).remove(task)
        val updatedTask = task.toBuilder
          .addStatuses(status)
          .build
        get(appId) += updatedTask
        store(appId, updatedTask).map(_ => Some(updatedTask))

      case _ =>
        log.warn(s"No task for ID ${taskId}")
        Future.successful(None)
    }
  }

  def checkStagedTasks: Iterable[MarathonTask] = {
    // stagedAt is set when the task is created by the scheduler
    val now = System.currentTimeMillis
    val expires = now - Main.conf.taskLaunchTimeout()
    val toKill = apps.values.map { app =>
      app.tasks.filter(t => Option(t.getStartedAt).isEmpty && t.getStagedAt < expires)
    }.flatten

    toKill.foreach(t => {
      log.warn(s"Task '${t.getId}' was staged ${(now - t.getStagedAt) / 1000}s ago and has not yet started")
    })
    toKill
  }

  def expungeOrphanedTasks() {
    // Remove tasks that don't have any tasks associated with them. Expensive!
    log.info("Expunging orphaned tasks from store")
    val stateTaskKeys = state.names.get.asScala.filter(_.startsWith(PREFIX))
    val appsTaskKeys = apps.values.flatMap { app =>
      app.tasks.map(task => getKey(app.appName, task.getId))
    }.toSet

    for (stateTaskKey <- stateTaskKeys) {
      if (!appsTaskKeys.contains(stateTaskKey)) {
        log.info(s"Expunging orphaned task with key ${stateTaskKey}")
        val variable = state.fetch(stateTaskKey).get
        state.expunge(variable)
      }
    }
  }

  private[tasks] def fetchApp(appId: PathId): App = {
    log.debug(s"Fetching app from store ${appId}")
    val names = state.names().get.asScala.toSet
    val tasks: mutable.Set[MarathonTask] = new mutable.HashSet[MarathonTask]
    val taskKeys = names.filter(name => name.startsWith(PREFIX + appId.toString + ID_DELIMITER))
    for (taskKey <- taskKeys) {
      fetchTask(taskKey) match {
        case Some(task) => tasks += task
        case None       => //no-op
      }
    }
    new App(appId, tasks, false)
  }

  def fetchTask(taskKey: String): Option[MarathonTask] = {
    val bytes = fetchFromState(taskKey).value
    if (bytes.length > 0) {
      val source = new ObjectInputStream(new ByteArrayInputStream(bytes))
      deserialize(taskKey, source)
    }
    else None
  }

  def deserialize(taskKey: String, source: ObjectInputStream): Option[MarathonTask] = {
    if (source.available > 0) {
      try {
        val size = source.readInt
        val bytes = new Array[Byte](size)
        source.readFully(bytes)
        Some(MarathonTask.parseFrom(bytes))
      }
      catch {
        case e: com.google.protobuf.InvalidProtocolBufferException =>
          log.warn(s"Unable to deserialize task state for $taskKey", e)
          None
      }
    }
    else {
      log.warn(s"Unable to deserialize task state for $taskKey")
      None
    }
  }

  def legacyDeserialize(appId: PathId, source: ObjectInputStream): mutable.HashSet[MarathonTask] = {
    var results = mutable.HashSet[MarathonTask]()

    if (source.available > 0) {
      try {
        val size = source.readInt
        val bytes = new Array[Byte](size)
        source.readFully(bytes)
        val app = MarathonApp.parseFrom(bytes)
        if (app.getName != appId.toString) {
          log.warn(s"App name from task state for $appId is wrong!  Got '${app.getName}' Continuing anyway...")
        }
        results ++= app.getTasksList.asScala.toSet
      }
      catch {
        case e: com.google.protobuf.InvalidProtocolBufferException =>
          log.warn(s"Unable to deserialize task state for $appId", e)
      }
    }
    else {
      log.warn(s"Unable to deserialize task state for $appId")
    }
    results
  }

  def serialize(task: MarathonTask, sink: ObjectOutputStream): Unit = {
    val size = task.getSerializedSize
    sink.writeInt(size)
    sink.write(task.toByteArray)
    sink.flush
  }

  def store(appId: PathId, task: MarathonTask): Future[Variable] = {
    val oldVar = fetchFromState(getKey(appId, task.getId))
    val bytes = new ByteArrayOutputStream()
    val output = new ObjectOutputStream(bytes)
    serialize(task, output)
    val newVar = oldVar.mutate(bytes.toByteArray)
    state.store(newVar)
  }

}

object TaskTracker {

  class App(
    val appName: PathId,
    var tasks: mutable.Set[MarathonTask],
    var shutdown: Boolean)

}
