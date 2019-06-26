package mesosphere.marathon
package integration

import mesosphere.{AkkaIntegrationTest, WhenEnvSet}
import mesosphere.marathon.integration.setup.{EmbeddedMarathonTest, MesosConfig}
import mesosphere.marathon.raml.{App, Container, DockerContainer, EngineType, LinuxInfo, Seccomp}
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId._

import scala.io.Source

class SeccompIntegratonTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  val projectDir: String = sys.props.getOrElse("user.dir", ".")
  override lazy val mesosConfig = MesosConfig(
    launcher = "linux",
    containerizers = "docker,mesos",
    isolation = Some("filesystem/linux,docker/runtime,linux/seccomp"),
    imageProviders = Some("docker"),
    agentSeccompConfigDir = Some(s"$projectDir/src/test/resources/mesos/seccomp"),
    agentSeccompProfileName = Some("default.json")
  )

  logger.info(s"Using --seccomp_config_dir = ${mesosConfig.agentSeccompConfigDir.get}")
  logger.info(s"Using --seccomp_profile_name = ${mesosConfig.agentSeccompProfileName.get}")
  logger.debug(s"Seccomp profile: ${Source.fromFile(s"$projectDir/src/test/resources/mesos/seccomp/default.json").getLines.mkString("\n")}")

  "An app definition WITH seccomp profile defined and unconfined = false" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("an app WITH seccomp profile defined and unconfined = false")
    val app = seccompApp(PathId("/app-with-seccomp-profile-and-unconfined-false"), unconfined = false, profileName = mesosConfig.agentSeccompProfileName)

    When("the app is successfully deployed")
    val result = marathon.createAppV2(app)
    result should be(Created)
    waitForDeployment(result)

    And("the task is running")
    waitForTasks(app.id.toPath, app.instances)
  }

  "An app definition WITHOUT seccomp profile and unconfined = true" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("an app WITHOUT seccomp profile and unconfined = true")
    val app = seccompApp(PathId("/app-without-seccomp-profile-and-unconfined-true"), unconfined = true)

    When("the app is successfully deployed")
    val result = marathon.createAppV2(app)
    result should be(Created)
    waitForDeployment(result)

    And("the task is running")
    waitForTasks(app.id.toPath, app.instances)
  }

  def seccompApp(appId: PathId, unconfined: Boolean, profileName: Option[String] = None): App = {
    App(
      id = appId.toString,
      cmd = Some("sleep 232323"),
      cpus = 0.01,
      mem = 32.0,
      container = Some(
        Container(
          `type` = EngineType.Mesos,
          docker = Some(DockerContainer(image = "busybox")),
          linuxInfo = Some(LinuxInfo(
            seccomp = Some(Seccomp(
              unconfined = unconfined,
              profileName = profileName
            ))
          ))
        )
      )
    )
  }
}
