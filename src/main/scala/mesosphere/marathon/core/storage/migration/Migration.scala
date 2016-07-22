package mesosphere.marathon.core.storage.migration

// scalastyle:off
import akka.Done
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.LegacyStorageConfig
import mesosphere.marathon.core.storage.migration.legacy.{ MigrationTo0_11, MigrationTo0_13, MigrationTo0_16, MigrationTo1_2 }
import mesosphere.marathon.core.storage.repository.impl.legacy.store.{ PersistentStore, PersistentStoreManagement }
import mesosphere.marathon.core.storage.repository.{ AppRepository, DeploymentRepository, GroupRepository, TaskFailureRepository, TaskRepository }
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.{ BuildInfo, MigrationFailedException }

import scala.async.Async.{ async, await }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal
// scalastyle:on

class Migration(
    private[migration] val legacyConfig: Option[LegacyStorageConfig],
    private[migration] val persistenceStore: Option[PersistenceStore[_, _, _]],
    private[migration] val appRepository: AppRepository,
    private[migration] val groupRepository: GroupRepository,
    private[migration] val deploymentRepository: DeploymentRepository,
    private[migration] val taskRepo: TaskRepository,
    private[migration] val taskFailureRepo: TaskFailureRepository)(implicit
  mat: Materializer,
    metrics: Metrics) extends StrictLogging {
  //scalastyle:off magic.number

  import StorageVersions._
  import Migration._

  type MigrationAction = (StorageVersion, () => Future[Any])

  private[migration] val minSupportedStorageVersion = StorageVersions(0, 8, 0)

  private[migration] val legacyStoreFuture: Future[Option[PersistentStore]] = legacyConfig.map { config =>
    val store = config.store
    store match {
      case s: PersistentStoreManagement =>
        s.initialize().map(_ => Some(store))
      case _ =>
        Future.successful(Some(store))
    }
  }.getOrElse(Future.successful(None))

  /**
    * All the migrations, that have to be applied.
    * They get applied after the master has been elected.
    */
  def migrations: List[MigrationAction] =
    List(
      StorageVersions(0, 7, 0) -> { () =>
        Future.failed(new IllegalStateException("migration from 0.7.x not supported anymore"))
      },
      StorageVersions(0, 11, 0) -> { () =>
        new MigrationTo0_11(legacyConfig).migrateApps().recover {
          case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.11", e)
        }
      },
      StorageVersions(0, 13, 0) -> { () =>
        new MigrationTo0_13(legacyConfig).migrate().recover {
          case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.13", e)
        }
      },
      StorageVersions(0, 16, 0) -> { () =>
        new MigrationTo0_16(legacyConfig).migrate().recover {
          case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.16", e)
        }
      },
      StorageVersions(1, 2, 0) -> { () =>
        new MigrationTo1_2(legacyConfig).migrate().recover {
          case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 1.2", e)
        }
      },
      StorageVersions(1, 3, 0) -> { () =>
        new MigrationTo1_3_PersistentStore(this).migrate().recover {
          case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 1.3", e)
        }
      }
    )

  def applyMigrationSteps(from: StorageVersion): Future[List[StorageVersion]] = {
    migrations.filter(_._1 > from).sortBy(_._1).foldLeft(Future.successful(List.empty[StorageVersion])) {
      case (resultsFuture, (migrateVersion, change)) => resultsFuture.flatMap { res =>
        logger.info(
          s"Migration for storage: ${from.str} to current: ${current.str}: " +
            s"apply change for version: ${migrateVersion.str} "
        )
        change.apply().map(_ => res :+ migrateVersion)
      }
    }
  }

  def migrate(): List[StorageVersion] = {
    val result = async {
      val legacyStore = await(legacyStoreFuture)
      // get the version out of persistence store, if that fails, get the version from the legacy store, if we're
      // using a legacy store.
      val currentVersion = await {
        persistenceStore.map(_.storageVersion()).orElse {
          legacyStore.map(_.load(StorageVersionName).map {
            case Some(v) => Some(StorageVersion.parseFrom(v.bytes.toArray))
            case None => None
          })
        }.getOrElse(Future.successful(Some(StorageVersions.current)))
      }

      val migrations = if (currentVersion.isDefined) {
        // can't use foreach as await isn't usable in nested functions.
        if (currentVersion.get < minSupportedStorageVersion) {
          val msg = s"Migration from versions < $minSupportedStorageVersion is not supported. " +
            s"Your version: ${currentVersion.get}"
          throw new MigrationFailedException(msg)
        } else {
          val applied = applyMigrationSteps(currentVersion.get)
          await(applied)
        }
      } else {
        logger.info("No migration necessary - new database")
        Nil
      }
      if (currentVersion.isEmpty || currentVersion.get < StorageVersions.current) {
        await(storeCurrentVersion)
      }
      await(closeLegacyStore)
      migrations
    }.recover {
      case ex: MigrationFailedException => throw ex
      case NonFatal(ex) => throw new MigrationFailedException(s"Migration Failed: ${ex.getMessage}", ex)
    }

    val migrations = Await.result(result, Duration.Inf)
    logger.info(s"Migration successfully applied for version ${StorageVersions.current}")
    migrations
  }

  private def storeCurrentVersion: Future[Done] = async {
    val legacyStore = await(legacyStoreFuture)
    persistenceStore.map(_.setStorageVersion(StorageVersions.current)).orElse {
      val bytes = StorageVersions.current.toByteArray
      legacyStore.map { store =>
        store.load(StorageVersionName).flatMap {
          case Some(entity) => store.update(entity.withNewContent(bytes))
          case None => store.create(StorageVersionName, bytes)
        }
      }
    }
    Done
  }

  private def closeLegacyStore: Future[Done] = async {
    val legacyStore = await(legacyStoreFuture)
    val future = legacyStore.map {
      case s: PersistentStoreManagement =>
        s.close()
      case _ =>
        Future.successful(Done)
    }.getOrElse(Future.successful(Done))
    await(future)
  }
}

object Migration {
  val StorageVersionName = "internal:storage:version"
}

object StorageVersions {
  val VersionRegex = """^(\d+)\.(\d+)\.(\d+).*""".r

  def apply(major: Int, minor: Int, patch: Int): StorageVersion = {
    StorageVersion
      .newBuilder()
      .setMajor(major)
      .setMinor(minor)
      .setPatch(patch)
      .build()
  }

  def current: StorageVersion = {
    BuildInfo.version match {
      case VersionRegex(major, minor, patch) =>
        StorageVersions(
          major.toInt,
          minor.toInt,
          patch.toInt
        )
    }
  }

  implicit class OrderedStorageVersion(val version: StorageVersion) extends AnyVal with Ordered[StorageVersion] {
    override def compare(that: StorageVersion): Int = {
      def by(left: Int, right: Int, fn: => Int): Int = if (left.compareTo(right) != 0) left.compareTo(right) else fn
      by(version.getMajor, that.getMajor, by(version.getMinor, that.getMinor, by(version.getPatch, that.getPatch, 0)))
    }

    def str: String = s"Version(${version.getMajor}, ${version.getMinor}, ${version.getPatch})"

    def nonEmpty: Boolean = !version.equals(empty)
  }

  def empty: StorageVersion = StorageVersions(0, 0, 0)
}
