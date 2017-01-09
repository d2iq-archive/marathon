package mesosphere.marathon
package integration

//import java.nio.file.Files
//import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import org.scalatest.concurrent.TimeLimits
import org.scalatest.time.{ Seconds, Span }

//import scala.sys.process.Process

class MarathonStartupIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with TimeLimits {

  "Marathon" should {
    "fail during start, if the HTTP port is already bound" in {
      Given(s"a Marathon process already running on port ${marathonServer.httpPort}")

      When("starting another Marathon process using an HTTP port that is already bound")

      val args = Map(
        "http_port" -> marathonServer.httpPort.toString,
        "zk_timeout" -> "2000"
      )
      val conflictingMarathon = LocalMarathon(true, s"$suiteName-conflict", marathonServer.masterUrl, marathonServer.zkUrl, args)

      Then("The Marathon process should exit with code > 0")
      try {
        failAfter(Span(40, Seconds)) {
          conflictingMarathon.exitValue().get should be(1)
        }
      } finally {
        // Destroy process if it did not exit in time.
        conflictingMarathon.stop()
      }
    }
  }
}
