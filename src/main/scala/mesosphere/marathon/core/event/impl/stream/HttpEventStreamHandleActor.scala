package mesosphere.marathon
package core.event.impl.stream

import java.io.EOFException

import akka.actor.{ Actor, Status }
import akka.event.EventStream
import akka.pattern.pipe
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.impl.stream.HttpEventStreamActor.SerializedMarathonEvent
import mesosphere.marathon.core.event.impl.stream.HttpEventStreamHandleActor._
import mesosphere.marathon.core.event.{ EventStreamAttached, EventStreamDetached }
import mesosphere.util.ThreadPoolContext

import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

class HttpEventStreamHandleActor(
    handle: HttpEventStreamHandle,
    stream: EventStream,
    maxOutStanding: Int) extends Actor with StrictLogging {

  private[impl] var outstanding = Seq.empty[SerializedMarathonEvent]

  override def preStart(): Unit = {
    stream.publish(EventStreamAttached(handle.remoteAddress))
  }

  override def postStop(): Unit = {
    logger.info(s"Stop actor $handle")
    stream.publish(EventStreamDetached(handle.remoteAddress))
    Try(handle.close()) //ignore, if this fails
  }

  override def receive: Receive = waitForEvent

  def waitForEvent: Receive = {
    case event: SerializedMarathonEvent =>
      outstanding = event +: outstanding
      sendAllMessages()
  }

  def stashEvents: Receive = handleWorkDone orElse {
    case event: SerializedMarathonEvent if outstanding.size >= maxOutStanding => dropEvent(event)
    case event: SerializedMarathonEvent => outstanding = event +: outstanding
  }

  def handleWorkDone: Receive = {
    case WorkDone => sendAllMessages()
    case Status.Failure(ex) =>
      handleException(ex)
      sendAllMessages()
  }

  private[this] def sendAllMessages(): Unit = {
    if (outstanding.nonEmpty) {
      val toSend = outstanding.reverse
      outstanding = List.empty[SerializedMarathonEvent]
      context.become(stashEvents)
      val sendFuture = Future {
        toSend.foreach(event => handle.sendEvent(event))
        WorkDone
      }(ThreadPoolContext.ioContext)

      import context.dispatcher
      sendFuture pipeTo self
    } else {
      context.become(waitForEvent)
    }
  }

  private[this] def handleException(ex: Throwable): Unit = ex match {
    case eof: EOFException =>
      logger.info(s"Received EOF from stream handle $handle. Ignore subsequent events.")
      //We know the connection is dead, but it is not finalized from the container.
      //Do not act any longer on any event.
      context.become(Actor.emptyBehavior)
    case NonFatal(e) =>
      logger.warn(s"Could not send message to $handle reason:", e)
  }

  private[this] def dropEvent(event: SerializedMarathonEvent): Unit = {
    logger.warn(s"Ignore event $event for handle $handle (slow consumer)")
  }
}

object HttpEventStreamHandleActor {
  object WorkDone
}

