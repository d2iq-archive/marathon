package mesosphere.marathon
package integration

import java.io.File
import java.net.URL
import java.nio.file.Files

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.Materializer
import mesosphere.marathon.integration.facades.{ ITEnrichedTask, MarathonFacade }
import mesosphere.{ AkkaIntegrationTest, WhenEnvSet }
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.io.IO
import mesosphere.marathon.state.PathId
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.{ HavePropertyMatchResult, HavePropertyMatcher }

import scala.concurrent.ExecutionContext
import scala.sys.process.Process

/**
  * This integration test starts older Marathon versions one after another and finishes this upgrade procedure with the
  * current build. In each step we verfiy that all apps are still up and running.
  */
@IntegrationTest
class UpgradeIntegrationTest extends AkkaIntegrationTest with MesosClusterTest with ZookeeperServerTest with Eventually {

  // Integration tests using docker image provisioning with the Mesos containerizer need to be
  // run as root in a Linux environment. They have to be explicitly enabled through an env variable.
  val envVar = "RUN_MESOS_INTEGRATION_TESTS"

  import PathId._

  val zkURL = s"zk://${zkServer.connectUri}/marathon-$suiteName"

  val marathon149 = Marathon149(suiteName = s"$suiteName-1-4-9", mesosMasterUrl, zkURL)
  val marathon156 = Marathon156(suiteName = s"$suiteName-1-5-6", mesosMasterUrl, zkURL)
  val marathon16322 = Marathon16322(suiteName = s"$suiteName-1-6-322", mesosMasterUrl, zkURL)
  val marathonCurrent = LocalMarathon(suiteName = s"$suiteName-current", masterUrl = mesosMasterUrl, zkUrl = zkURL)

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Download Marathon releases
    marathon149.downloadAndExtract()
    marathon156.downloadAndExtract()
    marathon16322.downloadAndExtract()
  }

  //  override def beforeEach(): Unit = {
  //    super.beforeEach()
  //    zkServer.start()
  //  }
  //
  //  override def afterEach(): Unit = {
  //    super.afterEach()
  //
  //    // Make sure that next test starts with fresh ZooKeeper instance.
  //    zkServer.close()
  //  }

  case class Marathon149(suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon {

    val marathon149Package = Files.createTempDirectory("marathon-1.4.9").toFile
    marathon149Package.deleteOnExit()

    def downloadAndExtract() = {
      val tarball = new File(marathon149Package, "marathon-1.4.9.tgz")
      logger.info(s"Downloading Marathon 1.4.9 to ${tarball.getCanonicalPath}")
      FileUtils.copyURLToFile(new URL("https://downloads.mesosphere.com/marathon/releases/1.4.9/marathon-1.4.9.tgz"), tarball)
      IO.extractTGZip(tarball, marathon149Package)
    }

    override val processBuilder = {
      val java = sys.props.get("java.home").fold("java")(_ + "/bin/java")
      val jar = new File(marathon149Package, "marathon-1.4.9/target/scala-2.11/marathon-assembly-1.4.9.jar").getCanonicalPath
      val cmd = Seq(java, "-Xmx1024m", "-Xms256m", "-XX:+UseConcMarkSweepGC", "-XX:ConcGCThreads=2",
        // lower the memory pressure by limiting threads.
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
        "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
        "-Dscala.concurrent.context.minThreads=2",
        "-Dscala.concurrent.context.maxThreads=32",
        s"-DmarathonUUID=$uuid -DtestSuite=$suiteName", "-client",
        "-jar", jar
      ) ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  case class Marathon156(suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon {

    val marathon156Package = Files.createTempDirectory("marathon-1.5.6").toFile
    marathon156Package.deleteOnExit()

    def downloadAndExtract() = {
      val tarball = new File(marathon156Package, "marathon-1.5.6.tgz")
      logger.info(s"Downloading Marathon 1.5.6 to ${tarball.getCanonicalPath}")
      FileUtils.copyURLToFile(new URL("https://downloads.mesosphere.com/marathon/releases/1.5.6/marathon-1.5.6.tgz"), tarball)
      IO.extractTGZip(tarball, marathon156Package)
    }

    override val processBuilder = {
      val bin = new File(marathon156Package, "marathon-1.5.6/bin/marathon").getCanonicalPath
      val cmd = Seq("bash", bin, "-J-Xmx1024m", "-J-Xms256m", "-J-XX:+UseConcMarkSweepGC", "-J-XX:ConcGCThreads=2",
        // lower the memory pressure by limiting threads.
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
        "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
        "-Dscala.concurrent.context.minThreads=2",
        "-Dscala.concurrent.context.maxThreads=32",
        s"-DmarathonUUID=$uuid -DtestSuite=$suiteName"
      ) ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  case class Marathon16322(suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon {

    val marathon16322Package = Files.createTempDirectory("marathon-1.6.322").toFile
    marathon16322Package.deleteOnExit()

    def downloadAndExtract() = {
      val tarball = new File(marathon16322Package, "marathon-1.6.322-2bf46b341.tgz")
      logger.info(s"Downloading Marathon 1.6.322 to ${tarball.getCanonicalPath}")
      FileUtils.copyURLToFile(new URL("https://downloads.mesosphere.com/marathon/releases/1.6.322/marathon-1.6.322-2bf46b341.tgz"), tarball)
      IO.extractTGZip(tarball, marathon16322Package)
    }

    override val processBuilder = {
      val bin = new File(marathon16322Package, "marathon-1.6.322-2bf46b341/bin/marathon").getCanonicalPath
      val cmd = Seq("bash", bin, "-J-Xmx1024m", "-J-Xms256m", "-J-XX:+UseConcMarkSweepGC", "-J-XX:ConcGCThreads=2",
        // lower the memory pressure by limiting threads.
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
        "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
        "-Dscala.concurrent.context.minThreads=2",
        "-Dscala.concurrent.context.maxThreads=32",
        s"-DmarathonUUID=$uuid -DtestSuite=$suiteName"
      ) ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  "Ephemeral and persistent apps and pods" should {
    "survive an upgrade cycle" taggedAs WhenEnvSet(envVar, default = "true") in {

      // Start apps in 1.4.9
      Given("A Marathon 1.4.9 is running")
      val f = new Fixture()
      marathon149.start().futureValue
      (marathon149.client.info.entityJson \ "version").as[String] should be("1.4.9")

      And("new running apps in Marathon 1.4.9")
      val app_149_fail = f.appProxy(f.testBasePath / "app-149-fail", "v1", instances = 1, healthCheck = None)
      marathon149.client.createAppV2(app_149_fail) should be(Created)

      val app_149 = f.appProxy(f.testBasePath / "app-149", "v1", instances = 1, healthCheck = None)
      marathon149.client.createAppV2(app_149) should be(Created)

      eventually { marathon149 should have (runningTasksFor(app_149.id.toPath, 1)) }
      eventually { marathon149 should have (runningTasksFor(app_149_fail.id.toPath, 1)) }

      val originalApp149Tasks = marathon149.client.tasks(app_149.id.toPath).value
      val originalApp149FailedTasks = marathon149.client.tasks(app_149_fail.id.toPath).value

      When("Marathon 1.4.9 is shut down")
      marathon149.stop().futureValue

      And(s"App ${app_149_fail.id} fails")
      killTask("app-149-fail")

      // Pass upgrade to 1.5.6
      And("Marathon is upgraded to 1.5.6")
      marathon156.start().futureValue
      (marathon156.client.info.entityJson \ "version").as[String] should be("1.5.6")

      And("new apps in Marathon 1.5.6 are added")
      val app_156 = f.appProxy(f.testBasePath / "app-156", "v1", instances = 1, healthCheck = None)
      marathon156.client.createAppV2(app_156) should be(Created)

      val app_156_fail = f.appProxy(f.testBasePath / "app-156-fail", "v1", instances = 1, healthCheck = None)
      marathon156.client.createAppV2(app_156_fail) should be(Created)

      Then("All apps from 1.5.6 are running")
      eventually { marathon156 should have (runningTasksFor(app_156.id.toPath, 1)) }
      eventually { marathon156 should have (runningTasksFor(app_156_fail.id.toPath, 1)) }

      val originalApp156Tasks = marathon156.client.tasks(app_156.id.toPath).value
      val originalApp156FailedTasks = marathon156.client.tasks(app_156_fail.id.toPath).value

      And("All apps from 1.4.9 are still running")
      marathon156.client.tasks(app_149.id.toPath).value should be(originalApp149Tasks)

      When("Marathon 1.5.6 is shut down")
      marathon156.stop().futureValue

      And(s"App ${app_156_fail.id} fails")
      killTask("app-156-fail")

      // Pass upgrade to 1.6.322
      And("Marathon is upgraded to 1.6.322")
      marathon16322.start().futureValue
      (marathon16322.client.info.entityJson \ "version").as[String] should be("1.6.322")

      Then("All apps from 1.4.9 and 1.5.6 are still running")
      marathon16322.client.tasks(app_149.id.toPath).value should be(originalApp149Tasks)
      marathon16322.client.tasks(app_156.id.toPath).value should be(originalApp156Tasks)

      // Pass upgrade to current
      When("Marathon is upgraded to the current version")
      marathon16322.stop().futureValue
      marathonCurrent.start().futureValue
      (marathonCurrent.client.info.entityJson \ "version").as[String] should be("1.6.0-SNAPSHOT")

      Then("All apps from 1.4.9 and 1.5.6 are still running")
      marathonCurrent.client.tasks(app_149.id.toPath).value should be(originalApp149Tasks)
      marathonCurrent.client.tasks(app_156.id.toPath).value should be(originalApp156Tasks)

      And("All apps from 1.4.9 and 1.5.6 are recovered and running again")
      eventually { marathonCurrent should have(runningTasksFor(app_149_fail.id.toPath, 1)) }
      marathonCurrent.client.tasks(app_149_fail.id.toPath).value should not be (originalApp149FailedTasks)

      eventually { marathonCurrent should have(runningTasksFor(app_156_fail.id.toPath, 1)) }
      marathonCurrent.client.tasks(app_156_fail.id.toPath).value should not be (originalApp156FailedTasks)

      marathonCurrent.close()
    }
  }

  def killTask(appName: String): Unit = {
    val pidPattern = """([^\s]+)\s+([^\s]+)\s+.*""".r
    val pids = Process("ps aux").!!.split("\n").filter { process =>
      process.contains("src/test/python/app_mock.py") && process.contains(appName)
    }.collect {
      case pidPattern(_, pid) => pid
    }

    Process(s"kill -9 ${pids.mkString(" ")}").!
    logger.info(s"Killed tasks of app $appName with PIDs ${pids.mkString(" ")}")
  }

  /**
    * Scala [[HavePropertyMatcher]] that checks that numberOfTasks are in running state for app appId on given Marathon.
    *
    * Do not use the class directly but [[UpgradeIntegrationTest.runningTasksFor]]:
    *
    * {{{
    *   marathon149 should have(runningTasksFor(app_149.id.toPath, 2))
    * }}}
    *
    * @param appId The app the is checked for running tasks.
    * @param numberOfTasks The number of tasks that should be running.
    */
  class RunningTasksMatcher(appId: PathId, numberOfTasks: Int) extends HavePropertyMatcher[BaseMarathon, List[ITEnrichedTask]] {
    def apply(marathon: BaseMarathon): HavePropertyMatchResult[List[ITEnrichedTask]] = {
      val tasks = marathon.client.tasks(appId).value
      val notRunningTasks = tasks.filter(_.state != "TASK_RUNNING")
      val matches = tasks.size == numberOfTasks && notRunningTasks.size == 0
      HavePropertyMatchResult(matches, "runningTasks", List.empty, notRunningTasks)
    }
  }

  def runningTasksFor(appId: PathId, numberOfTasks: Int) = new RunningTasksMatcher(appId, numberOfTasks)

  // TODO(karsten): I'd love to have this test extend MarathonTest but I get NullPointerException for Akka.
  case class Fixture()(
      implicit
      val system: ActorSystem,
      val mat: Materializer,
      val ctx: ExecutionContext,
      val scheduler: Scheduler) extends MarathonTest {

    override protected val logger = UpgradeIntegrationTest.this.logger
    override def marathonUrl: String = ???
    override def marathon = ???
    override def mesos = UpgradeIntegrationTest.this.mesos
    override val testBasePath = PathId("/")
    override val suiteName: String = UpgradeIntegrationTest.this.suiteName
    override implicit def patienceConfig: PatienceConfig = PatienceConfig(UpgradeIntegrationTest.this.patienceConfig.timeout, UpgradeIntegrationTest.this.patienceConfig.interval)
    override def leadingMarathon = ???
  }
}
