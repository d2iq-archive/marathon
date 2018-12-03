package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.Done
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.{agentFormat, AgentInfo, Id, InstanceState, tasksMapFormat}
import mesosphere.marathon.core.instance.{Goal, Reservation}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.{Instance, Timestamp, UnreachableStrategy}
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json.{JsValue, Json, Reads, _}

import scala.concurrent.{ExecutionContext, Future}

class MigrationTo160(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    MigrationTo160.migrateReservations(instanceRepository, persistenceStore)
  }
}

object MigrationTo160 extends MaybeStore with StrictLogging {

  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  /**
    * Read format for instance state without goal.
    */
  val instanceStateReads160: Reads[InstanceState] = {
    (
      (__ \ "condition").read[Condition] ~
      (__ \ "since").read[Timestamp] ~
      (__ \ "activeSince").readNullable[Timestamp] ~
      (__ \ "healthy").readNullable[Boolean]
    ) { (condition, since, activeSince, healthy) =>
        InstanceState(condition, since, activeSince, healthy, Goal.Running)
      }
  }

  /**
    * Read format for old instance without goal.
    */
  val instanceJsonReads160: Reads[Instance] = {
    (
      (__ \ "instanceId").read[Id] ~
      (__ \ "agentInfo").read[AgentInfo] ~
      (__ \ "tasksMap").read[Map[Task.Id, Task]] ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[InstanceState](instanceStateReads160) ~
      (__ \ "unreachableStrategy").readNullable[raml.UnreachableStrategy] ~
      (__ \ "reservation").readNullable[Reservation]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, maybeUnreachableStrategy, reservation) =>
        val unreachableStrategy = maybeUnreachableStrategy.
          map(Raml.fromRaml(_)).getOrElse(UnreachableStrategy.default())
        new Instance(instanceId, Some(agentInfo), state, tasksMap, runSpecVersion, unreachableStrategy, reservation)
      }
  }

  /**
    * This function traverses all instances in ZK, and moves reservation objects from tasks to the instance level.
    */
  def migrateReservations(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _])(implicit mat: Materializer): Future[Done] = {

    logger.info("Starting reservations migration to 1.6.0")

    implicit val instanceResolver: IdResolver[Id, JsValue, String, ZkId] =
      new IdResolver[Id, JsValue, String, ZkId] {
        override def toStorageId(id: Id, version: Option[OffsetDateTime]): ZkId =
          ZkId(category, id.idString, version)

        override val category: String = "instance"

        override def fromStorageId(key: ZkId): Id = Id.fromIdString(key.id)

        override val hasVersions: Boolean = false

        override def version(v: JsValue): OffsetDateTime = OffsetDateTime.MIN
      }

    implicit val instanceJsonUnmarshaller: Unmarshaller[ZkSerialized, JsValue] =
      Unmarshaller.strict {
        case ZkSerialized(byteString) =>
          Json.parse(byteString.utf8String)
      }

    import Reservation.reservationFormat

    def extractInstanceAndReservationsFromJson(jsValue: JsValue): Option[(Reservation, Instance)] = {
      val instance = jsValue.as[Instance](instanceJsonReads160)
      // Prior to Marathon 1.6.0, persistent volumes are supported only with apps,
      // therefore reservation objects can only appear in app instances, and since
      // an app has only one task by definition, there is only one KV pair in a taskMap
      // object.
      //
      // We use .headOption here to handle the case of apps with no persistent volumes.
      val maybeReservationJson = (jsValue \ "tasksMap" \\ "reservation").headOption

      maybeReservationJson.map { reservationJson =>
        Some(reservationJson.as[Reservation] -> instance)
      } getOrElse {
        None
      }
    }

    def checkExistingReservationAndUpdate(reservation: Reservation, instance: Instance): Option[Instance] = {
      instance.reservation match {
        case Some(_) =>
          //do nothing in case instance already contains some reservations, we don't want to not overwrite existing data
          None
        case None =>
          //no reservation on the instance level, updating instance with provided reservation
          val updatedInstance = instance.copy(reservation = Some(reservation))
          Some(updatedInstance)
      }
    }

    maybeStore(persistenceStore).map { store =>
      instanceRepository
        .ids()
        .mapAsync(1) { instanceId =>
          store.get(instanceId)
        }
        .mapConcat {
          case Some(jsValue) =>
            extractInstanceAndReservationsFromJson(jsValue).toList
          case _ =>
            Nil
        }
        .mapConcat {
          case (reservation, instance) =>
            checkExistingReservationAndUpdate(reservation, instance).toList
        }
        .mapAsync(1) { updatedInstance =>
          instanceRepository.store(updatedInstance)
        }
        .runWith(Sink.ignore)
    } getOrElse {
      Future.successful(Done)
    }
  }
}
