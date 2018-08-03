package mesosphere.marathon

import mesosphere.UnitTest
import mesosphere.marathon.state.ResourceRole
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.marathon.ZookeeperConf.ZkUrl

import scala.util.{Failure, Try}

class MarathonConfTest extends UnitTest {
  private[this] val principal = "foo"
  private[this] val secretFile = "/bar/baz"

  "MarathonConf" should {
    "MesosAuthenticationIsOptional" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050"
      )
      assert(conf.mesosAuthenticationPrincipal.isEmpty)
      assert(conf.mesosAuthenticationSecretFile.isEmpty)
      assert(conf.checkpoint.toOption == Some(true))
    }

    "MesosAuthenticationPrincipal" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--mesos_authentication_principal", principal
      )
      assert(conf.mesosAuthenticationPrincipal.isDefined)
      assert(conf.mesosAuthenticationPrincipal.toOption == Some(principal))
      assert(conf.mesosAuthenticationSecretFile.isEmpty)
    }

    "MesosAuthenticationSecretFile" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--mesos_authentication_principal", principal,
        "--mesos_authentication_secret_file", secretFile
      )
      assert(conf.mesosAuthenticationPrincipal.isDefined)
      assert(conf.mesosAuthenticationPrincipal.toOption == Some(principal))
      assert(conf.mesosAuthenticationSecretFile.isDefined)
      assert(conf.mesosAuthenticationSecretFile.toOption == Some(secretFile))
    }

    "--master" should {
      "allow a valid zookeeper URL" in {
        val conf = MarathonTestHelper.makeConfig("--master", "zk://127.0.0.1:2181/mesos")
        conf.mesosMaster() shouldBe MarathonConf.MesosMasterConnection.Zk(ZkUrl.parse("zk://127.0.0.1:2181/mesos").right.get)
      }

      "reject an invalid zookeeper URL" in {
        Try(MarathonTestHelper.makeConfig("--master", "zk://127.0.0.1:lol/mesos")).isFailure shouldBe true
      }

      "allows an HTTP URL" in {
        val conf = MarathonTestHelper.makeConfig("--master", "http://127.0.0.1:5050")
        conf.mesosMaster() shouldBe MarathonConf.MesosMasterConnection.Http(new java.net.URL("http://127.0.0.1:5050"))
      }

      "allows an unspecified protocol" in {
        val conf = MarathonTestHelper.makeConfig("--master", "127.0.0.1:5050")
        conf.mesosMaster() shouldBe MarathonConf.MesosMasterConnection.Unspecified("127.0.0.1:5050")
      }
    }

    "Secret can be specified directly" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--mesos_authentication_principal", principal,
        "--mesos_authentication_secret", "top secret"
      )
      assert(conf.mesosAuthenticationSecretFile.isEmpty)
      assert(conf.mesosAuthenticationPrincipal.toOption.contains(principal))
      assert(conf.mesosAuthenticationSecret.toOption.contains("top secret"))
    }

    "Secret and SecretFile can not be specified at the same time" in {
      Try(MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--mesos_authentication_principal", principal,
        "--mesos_authentication_secret", "top secret",
        "--mesos_authentication_secret_file", secretFile
      )) match {
        case Failure(ex) => ex.getMessage should include("There should be only one or zero of the following options: mesos_authentication_secret, mesos_authentication_secret_file")
        case _ => fail("Should give an error")
      }
    }

    "HA mode is enabled by default" in {
      val conf = MarathonTestHelper.defaultConfig()
      assert(conf.highlyAvailable())
    }

    "Disable HA mode" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--disable_ha"
      )
      assert(!conf.highlyAvailable())
    }

    "Checkpointing is enabled by default" in {
      val conf = MarathonTestHelper.defaultConfig()
      assert(conf.checkpoint())
    }

    "Disable checkpointing" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--disable_checkpoint"
      )
      assert(!conf.checkpoint())
    }

    "--default_accepted_resource_roles *,marathon will fail without --mesos_role marathon" in {
      val triedConfig = Try(MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--default_accepted_resource_roles", "*,marathon"
      )
      )
      assert(triedConfig.isFailure)
      triedConfig match {
        case Failure(e) if e.getMessage ==
          "requirement failed: " +
          "--default_accepted_resource_roles contains roles for which we will not receive offers: marathon" =>
        case other =>
          fail(s"unexpected triedConfig: $other")
      }
    }

    "--default_accepted_resource_roles *,marathon with --mesos_role marathon" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--mesos_role", "marathon",
        "--default_accepted_resource_roles", "*,marathon"
      )

      assert(conf.defaultAcceptedResourceRolesSet == Set(ResourceRole.Unreserved, "marathon"))
    }

    "--default_accepted_resource_roles *" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--default_accepted_resource_roles", "*"
      )
      assert(conf.defaultAcceptedResourceRolesSet == Set(ResourceRole.Unreserved))
    }

    "--default_accepted_resource_roles default without --mesos_role" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050"
      )
      assert(conf.defaultAcceptedResourceRolesSet == Set(ResourceRole.Unreserved))
    }

    "--default_accepted_resource_roles default with --mesos_role" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--mesos_role", "marathon"
      )
      assert(conf.defaultAcceptedResourceRolesSet == Set(ResourceRole.Unreserved, "marathon"))
    }
  }
}
