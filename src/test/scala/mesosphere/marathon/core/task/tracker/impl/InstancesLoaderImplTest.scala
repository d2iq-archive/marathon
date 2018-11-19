package mesosphere.marathon
package core.task.tracker.impl

import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{PathId, Instance => StateInstance}
import mesosphere.marathon.storage.repository.{GroupRepository, InstanceRepository}

import scala.concurrent.Future

class InstancesLoaderImplTest extends AkkaUnitTest {
  class Fixture {
    lazy val instanceRepository = mock[InstanceRepository]
    lazy val groupRepository = mock[GroupRepository]
    lazy val loader = new InstancesLoaderImpl(instanceRepository, groupRepository)

    def verifyNoMoreInteractions(): Unit = noMoreInteractions(instanceRepository)
  }
  "InstanceLoaderImpl" should {
    "load no instances" in {
      val f = new Fixture

      Given("no tasks")
      f.instanceRepository.ids() returns Source.empty

      When("loadTasks is called")
      val loaded = f.loader.load()

      Then("taskRepository.ids gets called")
      verify(f.instanceRepository).ids()

      And("our data is empty")
      loaded.futureValue.allInstances should be(empty)

      And("there are no more interactions")
      f.verifyNoMoreInteractions()
    }

    "load multiple instances for multiple apps" in {
      val f = new Fixture

      Given("instances for multiple runSpecs")
      val app1Id = PathId("/app1")
      val app1Instance1 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1Instance2 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1 = app1Instance1.runSpec
      f.groupRepository.runSpecVersion(eq(app1Id), eq(app1.version.toOffsetDateTime))(any) returns Future.successful(Some(app1))

      val app2Id = PathId("/app2")
      val app2Instance1 = TestInstanceBuilder.newBuilder(app2Id).getInstance()
      val app2 = app2Instance1.runSpec
      f.groupRepository.runSpecVersion(eq(app2Id), eq(app2.version.toOffsetDateTime))(any) returns Future.successful(Some(app2))

      val instances = Seq(
        StateInstance.fromCoreInstance(app1Instance1),
        StateInstance.fromCoreInstance(app1Instance2),
        StateInstance.fromCoreInstance(app2Instance1)
      )

      f.instanceRepository.ids() returns Source(instances.map(_.instanceId)(collection.breakOut))
      for (instance <- instances) {
        f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))
      }

      When("load is called")
      val loaded = f.loader.load()

      Then("the resulting data is correct")
      // we do not need to verify the mocked calls because the only way to get the data is to perform the calls
      val expectedData = InstanceTracker.InstancesBySpec.forInstances(app1Instance1, app1Instance2, app2Instance1)
      loaded.futureValue should equal(expectedData)
    }

    "ignore instances without a run spec" in {
      val f = new Fixture

      Given("instances for multiple runSpecs")
      val app1Id = PathId("/app1")
      val app1Instance1 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1Instance2 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1 = app1Instance1.runSpec
      f.groupRepository.runSpecVersion(eq(app1Id), eq(app1.version.toOffsetDateTime))(any) returns Future.successful(Some(app1))

      val app2Id = PathId("/app2")
      val app2Instance1 = TestInstanceBuilder.newBuilder(app2Id).getInstance()
      f.groupRepository.runSpecVersion(eq(app2Id), eq(app2Instance1.runSpecVersion.toOffsetDateTime))(any) returns Future.successful(None)

      val instances = Seq(
        StateInstance.fromCoreInstance(app1Instance1),
        StateInstance.fromCoreInstance(app1Instance2),
        StateInstance.fromCoreInstance(app2Instance1)
      )

      f.instanceRepository.ids() returns Source(instances.map(_.instanceId)(collection.breakOut))
      for (instance <- instances) {
        f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))
      }

      When("load is called")
      val loaded = f.loader.load()

      Then("the resulting data ")
      // we do not need to verify the mocked calls because the only way to get the data is to perform the calls
      val expectedData = InstanceTracker.InstancesBySpec.forInstances(app1Instance1, app1Instance2)
      loaded.futureValue should equal(expectedData)
    }
  }
}