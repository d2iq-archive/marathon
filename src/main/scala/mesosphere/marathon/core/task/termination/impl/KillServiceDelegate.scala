package mesosphere.marathon
package core.task.termination.impl

import akka.Done
import akka.actor.ActorRef
import akka.event.EventStream
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{KillReason, KillService}

import scala.collection.immutable.Seq
import scala.concurrent.Future

private[termination] class KillServiceDelegate(actorRef: ActorRef, eventStream: EventStream) extends KillService with StrictLogging {
  import KillServiceActor._

  override def killUnknownTask(taskId: Task.Id, reason: KillReason): Unit = {
    logger.info(s"Killing unknown task for reason: $reason (id: {})", taskId)
    actorRef ! KillUnknownTaskById(taskId)
  }

  override def killInstancesAndForget(instances: Seq[Instance], reason: KillReason): Unit = {
    if (instances.nonEmpty) {
      logger.info(s"Kill and forget following instances for reason $reason: ${instances.map(_.instanceId).mkString(",")}")
      actorRef ! KillInstancesAndForget(instances)
    }
  }

  /**
    * Begins watching immediately for terminated instances. Future is completed when all instances are seen.
    */
  def watchForKilledInstances(instances: Seq[Instance])(implicit materializer: Materializer): Future[Done] = {
    // Note - we toss the materialized cancellable. We are okay to do this here
    // because KillServiceActor will continue to retry killing the instanceIds
    // in question, forever, until this Future completes.
    KillStreamWatcher.
      watchForKilledInstances(eventStream, instances).
      runWith(Sink.head)(materializer)
  }
}
