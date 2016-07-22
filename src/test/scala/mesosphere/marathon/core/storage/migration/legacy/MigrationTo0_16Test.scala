/*package mesosphere.marathon.core.storage.migration.legacy

import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.storage.repository.impl.legacy.store.{InMemoryStore, MarathonStore}
import mesosphere.marathon.core.storage.repository.impl.legacy.{AppEntityRepository, GroupEntityRepository}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{AppDefinition, Group, PathId, PortDefinitions, Timestamp}
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.marathon.{MarathonSpec, Protos}
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{GivenWhenThen, Matchers}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

class MigrationTo0_16Test extends MarathonSpec with GivenWhenThen with Matchers with MarathonActorSupport {
  import mesosphere.FutureTestSupport._

  class Fixture {
    implicit lazy val metrics = new Metrics(new MetricRegistry)
    lazy val store = new InMemoryStore()

    lazy val appStore = new MarathonStore[AppDefinition](store, metrics, () => AppDefinition(), prefix = "app:")
    lazy val appRepo = new AppEntityRepository(appStore, maxVersions = 0)(ExecutionContext.global, metrics)

    lazy val groupStore = new MarathonStore[Group](store, metrics, () => Group.empty, prefix = "group:")
    lazy val groupRepo = new GroupEntityRepository(groupStore, maxVersions = 0, appRepo)

    lazy val migration = new MigrationTo0_16(groupRepository = groupRepo, appRepository = appRepo)
  }

  val emptyGroup = Group.empty

  implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(3, Seconds))

  test("empty migration does nothing") {
    Given("no apps/groups")
    val f = new Fixture

    When("migrating")
    f.migration.migrate().futureValue

    Then("only an empty root Group is created")
    val group = f.groupRepo.root().futureValue
    group.groups should be('empty)
    group.apps should be('empty)
    group.dependencies should be('empty)
    f.appRepo.ids().runWith(Sink.seq).futureValue should be('empty)
  }

  test("an app and all its revisions are migrated") {
    val f = new Fixture

    def appProtoInNewFormatAsserts(proto: Protos.ServiceDefinition) = {
      val ports = proto.getPortDefinitionsList.asScala.map(_.getNumber)
      assert(Seq(1000, 1001) == ports, ports)
      assert(proto.getPortsCount == 0)
    }

    def appProtoIsInNewFormat(version: Option[Long]): Unit = {
      def fetchAppProto(version: Option[Long]): Protos.ServiceDefinition = {
        val suffix = version.map { version => s":${Timestamp(version)}" }.getOrElse("")
        val entity = f.store.load(s"app:test$suffix").futureValue.get
        Protos.ServiceDefinition.parseFrom(entity.bytes.toArray)
      }

      appProtoInNewFormatAsserts(fetchAppProto(version))
    }

    def groupProtoIsInNewFormat(version: Option[Long]): Unit = {
      def fetchGroupProto(version: Option[Long]): Protos.GroupDefinition = {
        val suffix = version.map { version => s":${Timestamp(version)}" }.getOrElse("")
        val entity = f.store.load(s"group:${GroupEntityRepository.ZkRootName}$suffix").futureValue.get
        Protos.GroupDefinition.parseFrom(entity.bytes.toArray)
      }

      val proto = fetchGroupProto(version)
      proto.getAppsList.asScala.foreach(appProtoInNewFormatAsserts)
    }

    val appV1 = deprecatedAppDefinition(1)
    val appV2 = deprecatedAppDefinition(2)

    f.appRepo.store(appV1).futureValue
    f.appRepo.store(appV2).futureValue

    val groupWithApp = emptyGroup.copy(apps = Map(appV2.id -> appV2), version = Timestamp(2))
    f.groupRepo.storeRoot(groupWithApp, Nil, Nil).futureValue

    When("migrating")
    f.migration.migrate().futureValue

    Then("all the app protos must be in the new format")
    appProtoIsInNewFormat(None)
    appProtoIsInNewFormat(Some(1))
    appProtoIsInNewFormat(Some(2))

    Then("the apps in the group proto must be in the new format")
    groupProtoIsInNewFormat(None)
    groupProtoIsInNewFormat(Some(2))
  }

  test("A deprecatedAppDefinition serializes in the deprecated format") {
    val app = deprecatedAppDefinition()

    val proto = app.toProto

    proto.getPortDefinitionsCount should be(0)
    proto.getPortsList.asScala.map(_.toInt).toSet should be (Set(1000, 1001))
  }

  private[this] def deprecatedAppDefinition(version: Long = 0) =
    {
      class T extends AppDefinition(
        PathId("/test"),
        cmd = Some("true"),
        portDefinitions = PortDefinitions(1000, 1001),
        versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(version))
      ) with DeprecatedSerialization

      new T()
    }

  private[this] trait DeprecatedSerialization extends AppDefinition {
    override def toProto: Protos.ServiceDefinition = {
      val builder = super.toProto.toBuilder

      builder.getPortDefinitionsList.asScala.map(_.getNumber).map(builder.addPorts)
      builder.clearPortDefinitions()

      builder.build
    }
  }
}
*/ 