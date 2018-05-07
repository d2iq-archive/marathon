package mesosphere.marathon
package core.task.tracker.impl

import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.InstanceRepository
import mesosphere.marathon.stream.Sink

import scala.concurrent.Future

/**
  * Loads all task data into an [[InstanceTracker.InstancesBySpec]] from an [[InstanceRepository]].
  */
private[tracker] class InstancesLoaderImpl(repo: InstanceRepository)(implicit val mat: Materializer)
  extends InstancesLoader with StrictLogging {
  import scala.concurrent.ExecutionContext.Implicits.global

  override def load(): Future[InstanceTracker.InstancesBySpec] = {
    for {
      names <- repo.ids().runWith(Sink.seq)
      _ = logger.info(s"About to load ${names.size} tasks")
      instances <- Future.sequence(names.map(repo.get)).map(_.flatten)
    } yield {
      logger.info(s"Loaded ${instances.size} tasks")
      InstanceTracker.InstancesBySpec.forInstances(instances)
    }
  }
}
