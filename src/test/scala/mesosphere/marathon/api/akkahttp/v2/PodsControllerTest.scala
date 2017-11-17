package mesosphere.marathon
package api.akkahttp.v2

import java.net.InetAddress

import akka.event.EventStream
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Location, `Remote-Address` }
import mesosphere.{ UnitTest, ValidationTestLike }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.api.akkahttp.EntityMarshallers.ValidationFailed
import mesosphere.marathon.api.akkahttp.Headers
import mesosphere.marathon.api.v2.validation.NetworkValidationMessages
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.PodManager
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.util.SemanticVersion
import play.api.libs.json._
import play.api.libs.json.Json

import scala.concurrent.Future

class PodsControllerTest extends UnitTest with ScalatestRouteTest with RouteBehaviours with ValidationTestLike with ResponseMatchers {

  "PodsController" should {
    "support pods" in {
      val controller = Fixture().controller()
      Head(Uri./) ~> controller.route ~> check {
        response.status should be(StatusCodes.OK)
        responseAs[String] shouldBe empty
      }
    }

    {
      val controller = Fixture(authenticated = false).controller()
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Head(Uri./))
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Post(Uri./))
    }

    {
      val controller = Fixture(authorized = false).controller()
      val podSpecJson = """
                          | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
                          |   { "name": "webapp",
                          |     "resources": { "cpus": 0.03, "mem": 64 },
                          |     "image": { "kind": "DOCKER", "id": "busybox" },
                          |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                        """.stripMargin
      val entity = HttpEntity(podSpecJson).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = request)
    }

    "be able to create a simple single-container pod from docker image w/ shell command" in {
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJson = """
                          | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
                          |   { "name": "webapp",
                          |     "resources": { "cpus": 0.03, "mem": 64 },
                          |     "image": { "kind": "DOCKER", "id": "busybox" },
                          |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                        """.stripMargin
      val entity = HttpEntity(podSpecJson).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        response.status should be(StatusCodes.Created)
        response.header[Headers.`Marathon-Deployment-Id`].value.value() should be(deploymentPlan.id)
        response.header[Location].value.value() should be("/mypod")

        val jsonResponse: JsValue = Json.parse(responseAs[String])

        jsonResponse should have (
          executorResources(cpus = 0.1, mem = 32.0, disk = 10.0),
          noDefinedNetworkname,
          networkMode(raml.NetworkMode.Host)
        )
      }
    }

    "be able to create a simple single-container pod with bridge network" in {
      val f = Fixture(configArgs = Seq("--default_network_name", "blah"))
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithBridgeNetwork = """
                                           | { "id": "/mypod", "networks": [ { "mode": "container/bridge" } ], "containers": [
                                           |   { "name": "webapp",
                                           |     "resources": { "cpus": 0.03, "mem": 64 },
                                           |     "image": { "kind": "DOCKER", "id": "busybox" },
                                           |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                                         """.stripMargin
      val entity = HttpEntity(podSpecJsonWithBridgeNetwork).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        response.status should be(StatusCodes.Created)
        response.header[Headers.`Marathon-Deployment-Id`].value.value() should be(deploymentPlan.id)
        response.header[Location].value.value() should be("/mypod")

        val jsonResponse = Json.parse(responseAs[String])

        jsonResponse should have (
          executorResources (cpus = 0.1, mem = 32.0, disk = 10.0),
          noDefinedNetworkname,
          networkMode(raml.NetworkMode.ContainerBridge)
        )
      }
    }

    "The secrets feature is NOT enabled and create pod (that uses file base secrets) fails" in {
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithFileBasedSecret = """
                                             | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers":
                                             |   [
                                             |     { "name": "webapp",
                                             |       "resources": { "cpus": 0.03, "mem": 64 },
                                             |       "image": { "kind": "DOCKER", "id": "busybox" },
                                             |       "exec": { "command": { "shell": "sleep 1" } },
                                             |       "volumeMounts": [ { "name": "vol", "mountPath": "mnt2" } ]
                                             |     }
                                             |   ],
                                             |   "volumes": [ { "name": "vol", "secret": "secret1" } ],
                                             |   "secrets": { "secret1": { "source": "/path/to/my/secret" } }
                                             |  }
                                           """.stripMargin
      val entity = HttpEntity(podSpecJsonWithFileBasedSecret).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        rejection shouldBe a[ValidationFailed]
        inside(rejection) {
          case ValidationFailed(failure) =>
            failure should haveViolations("/podSecretVolumes(pod)" -> "Feature secrets is not enabled. Enable with --enable_features secrets)")
        }
      }
    }

    "The secrets feature is NOT enabled and create pod (that uses env secret refs) fails" in {
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithEnvRefSecret = """
                                          | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers":
                                          |   [
                                          |     { "name": "webapp",
                                          |       "resources": { "cpus": 0.03, "mem": 64 },
                                          |       "image": { "kind": "DOCKER", "id": "busybox" },
                                          |       "exec": { "command": { "shell": "sleep 1" } }
                                          |     }
                                          |   ],
                                          |   "environment": { "vol": { "secret": "secret1" } },
                                          |   "secrets": { "secret1": { "source": "/foo" } }
                                          |  }
                                        """.stripMargin
      val entity = HttpEntity(podSpecJsonWithEnvRefSecret).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        rejection shouldBe a[ValidationFailed]
        inside(rejection) {
          case ValidationFailed(failure) =>
            failure should haveViolations("/secrets" -> "Feature secrets is not enabled. Enable with --enable_features secrets)")
        }
      }
    }

    "The secrets feature is NOT enabled and create pod (that uses env secret refs on container level) fails" in {
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithEnvRefSecretOnContainerLevel = """
                                                          | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers":
                                                          |   [
                                                          |     { "name": "webapp",
                                                          |       "resources": { "cpus": 0.03, "mem": 64 },
                                                          |       "image": { "kind": "DOCKER", "id": "busybox" },
                                                          |       "exec": { "command": { "shell": "sleep 1" } },
                                                          |       "environment": { "vol": { "secret": "secret1" } }
                                                          |     }
                                                          |   ],
                                                          |   "secrets": { "secret1": { "source": "/path/to/my/secret" } }
                                                          |  }
                                                        """.stripMargin
      val entity = HttpEntity(podSpecJsonWithEnvRefSecretOnContainerLevel).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        rejection shouldBe a[ValidationFailed]
        inside(rejection) {
          case ValidationFailed(failure) =>
            failure should haveViolations("/secrets" -> "Feature secrets is not enabled. Enable with --enable_features secrets)")
        }
      }
    }

    "The secrets feature is enabled and create pod (that uses env secret refs on container level) succeeds" in {
      val f = Fixture(configArgs = Seq("--default_network_name", "blah", "--enable_features", Features.SECRETS)) // should not be injected into host network spec
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithEnvRefSecretOnContainerLevel = """
                                                          | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers":
                                                          |   [
                                                          |     { "name": "webapp",
                                                          |       "resources": { "cpus": 0.03, "mem": 64 },
                                                          |       "image": { "kind": "DOCKER", "id": "busybox" },
                                                          |       "exec": { "command": { "shell": "sleep 1" } },
                                                          |       "environment": { "vol": { "secret": "secret1" } }
                                                          |     }
                                                          |   ],
                                                          |   "secrets": { "secret1": { "source": "/path/to/my/secret" } }
                                                          |  }
                                                        """.stripMargin
      val entity = HttpEntity(podSpecJsonWithEnvRefSecretOnContainerLevel).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        response.status should be(StatusCodes.Created)

        val jsonResponse = Json.parse(responseAs[String])
        jsonResponse should have (podContainerWithEnvSecret("secret1"))
      }
    }

    "The secrets feature is enabled and create pod (that uses file based secrets) succeeds" in {
      val f = Fixture(configArgs = Seq("--default_network_name", "blah", "--enable_features", Features.SECRETS)) // should not be injected into host network spec
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithFileBasedSecret = """
                                             | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers":
                                             |   [
                                             |     { "name": "webapp",
                                             |       "resources": { "cpus": 0.03, "mem": 64 },
                                             |       "image": { "kind": "DOCKER", "id": "busybox" },
                                             |       "exec": { "command": { "shell": "sleep 1" } },
                                             |       "volumeMounts": [ { "name": "vol", "mountPath": "mnt2" } ]
                                             |     }
                                             |   ],
                                             |   "volumes": [ { "name": "vol", "secret": "secret1" } ],
                                             |   "secrets": { "secret1": { "source": "/path/to/my/secret" } }
                                             |  }
                                           """.stripMargin
      val entity = HttpEntity(podSpecJsonWithFileBasedSecret).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        response.status should be(StatusCodes.Created)

        val jsonResponse = Json.parse(responseAs[String])
        jsonResponse should have (podWithFileBasedSecret ("secret1"))
      }
    }

    "create a pod w/ container networking" in {
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // required since network name is missing from JSON
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithContainerNetworking = """
                                                 | { "id": "/mypod", "networks": [ { "mode": "container" } ], "containers": [
                                                 |   { "name": "webapp",
                                                 |     "resources": { "cpus": 0.03, "mem": 64 },
                                                 |     "image": { "kind": "DOCKER", "id": "busybox" },
                                                 |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                                               """.stripMargin
      val entity = HttpEntity(podSpecJsonWithContainerNetworking).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        response.status should be(StatusCodes.Created)
        response.header[Headers.`Marathon-Deployment-Id`].value.value() should be(deploymentPlan.id)
        response.header[Location].value.value() should be("/mypod")

        val jsonResponse = Json.parse(responseAs[String])

        jsonResponse should have(
          executorResources(cpus = 0.1, mem = 32.0, disk = 10.0),
          definedNetworkname("blah"),
          networkMode(raml.NetworkMode.Container)
        )
      }
    }

    "create a pod w/ container networking w/o default network name" in {
      val f = Fixture()
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithContainerNetworking = """
                                                 | { "id": "/mypod", "networks": [ { "mode": "container" } ], "containers": [
                                                 |   { "name": "webapp",
                                                 |     "resources": { "cpus": 0.03, "mem": 64 },
                                                 |     "image": { "kind": "DOCKER", "id": "busybox" },
                                                 |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                                               """.stripMargin
      val entity = HttpEntity(podSpecJsonWithContainerNetworking).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        rejection shouldBe a[ValidationFailed]
        inside(rejection) {
          case ValidationFailed(failure) =>
            failure should haveViolations("/networks" -> NetworkValidationMessages.NetworkNameMustBeSpecified)
        }
      }
    }

    "create a pod with custom executor resource declaration" in {
      val f = Fixture()
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithExecutorResources = """
                                               | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
                                               |   { "name": "webapp",
                                               |     "resources": { "cpus": 0.03, "mem": 64 },
                                               |     "image": { "kind": "DOCKER", "id": "busybox" },
                                               |     "exec": { "command": { "shell": "sleep 1" } } } ],
                                               |     "executorResources": { "cpus": 100, "mem": 100 } }
                                             """.stripMargin
      val entity = HttpEntity(podSpecJsonWithExecutorResources).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        response.status should be(StatusCodes.Created)
        response.header[Headers.`Marathon-Deployment-Id`].value.value() should be(deploymentPlan.id)
        response.header[Location].value.value() should be("/mypod")

        val jsonResponse = Json.parse(responseAs[String])

        jsonResponse should have(executorResources(cpus = 100.0, mem = 100.0, disk = 10.0))
      }
    }
  }

  case class Fixture(
      configArgs: Seq[String] = Seq.empty[String],
      authenticated: Boolean = true,
      authorized: Boolean = true,
      isLeader: Boolean = true) {
    val config = AllConf.withTestConfig(configArgs: _*)
    val clock = new SettableClock

    val auth = new TestAuthFixture()
    auth.authenticated = authenticated
    auth.authorized = authorized

    val electionService = mock[ElectionService]
    val groupManager = mock[GroupManager]
    val podManager = mock[PodManager]
    val pluginManager = PluginManager.None
    val eventBus = mock[EventStream]
    val scheduler = mock[MarathonScheduler]

    electionService.isLeader returns (isLeader)
    scheduler.mesosMasterVersion() returns Some(SemanticVersion(0, 0, 0))

    implicit val authenticator = auth.auth
    def controller() = new PodsController(config, electionService, podManager, groupManager, pluginManager, eventBus, scheduler, clock)
  }
}
