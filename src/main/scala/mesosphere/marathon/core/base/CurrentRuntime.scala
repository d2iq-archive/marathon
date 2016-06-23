package mesosphere.marathon.core.base

import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, TimeoutException, Await, Future }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.concurrent.blocking

object CurrentRuntime {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  /**
    * Exit this process in an async fashion.
    * First try exit regularly in the given timeout. If this does not exit in time, we halt the system.
    *
    * @param exitCode the exit code to signal.
    * @param waitForExit the time to wait for a normal exit.
    * @return the Future of this operation.
    */
  //scalastyle:off magic.number
  def asyncExit(exitCode: Int = 137, //Fatal error signal "n" is 128+n ==> n for killed is 9 ==> 137
                waitForExit: FiniteDuration = 10.seconds)(implicit ec: ExecutionContext): Future[Unit] = {
    Future(
      blocking {
        try {
          Await.result(Future(blocking(sys.exit(exitCode))), waitForExit)
        }
        catch {
          case _: TimeoutException => log.error("Shutdown timeout")
          case NonFatal(t)         => log.error("Exception while committing suicide", t)
        }

        log.info("Halting JVM")
        Runtime.getRuntime.halt(exitCode)
      }
    )
  }
}
