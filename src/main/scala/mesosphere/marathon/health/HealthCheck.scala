package mesosphere.marathon.health

import java.lang.{ Integer => JInt }

import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.validation.ValidHealthCheck
import mesosphere.marathon.state.{ Timestamp, MarathonState, Command }
import org.apache.mesos.{ Protos => MesosProtos }

import scala.concurrent.duration._

@ValidHealthCheck
case class HealthCheck(

  path: Option[String] = HealthCheck.DefaultPath,

  protocol: Protocol = HealthCheck.DefaultProtocol,

  portIndex: JInt = HealthCheck.DefaultPortIndex,

  command: Option[Command] = HealthCheck.DefaultCommand,

  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,

  interval: FiniteDuration = HealthCheck.DefaultInterval,

  timeout: FiniteDuration = HealthCheck.DefaultTimeout,

  maxConsecutiveFailures: JInt = HealthCheck.DefaultMaxConsecutiveFailures,

  ignoreHttp1xx: Boolean = HealthCheck.DefaultIgnoreHttp1xx)
    extends MarathonState[Protos.HealthCheckDefinition, HealthCheck] {

  def toProto: Protos.HealthCheckDefinition = {
    val builder = Protos.HealthCheckDefinition.newBuilder
      .setProtocol(this.protocol)
      .setPortIndex(this.portIndex)
      .setGracePeriodSeconds(this.gracePeriod.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)
      .setMaxConsecutiveFailures(this.maxConsecutiveFailures)
      .setIgnoreHttp1Xx(this.ignoreHttp1xx)

    command foreach { c => builder.setCommand(c.toProto) }

    path foreach builder.setPath

    builder.build
  }

  def mergeFromProto(proto: Protos.HealthCheckDefinition): HealthCheck =
    HealthCheck(
      path =
        if (proto.hasPath) Some(proto.getPath) else None,
      protocol = proto.getProtocol,
      portIndex = proto.getPortIndex,
      command =
        if (proto.hasCommand) Some(Command("").mergeFromProto(proto.getCommand))
        else None,
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures,
      ignoreHttp1xx = proto.getIgnoreHttp1Xx
    )

  def mergeFromProto(bytes: Array[Byte]): HealthCheck =
    mergeFromProto(Protos.HealthCheckDefinition.parseFrom(bytes))

  // Mesos supports COMMAND health checks, others to be added in the future
  def toMesos: MesosProtos.HealthCheck = {
    val builder = this.protocol match {
      case Protocol.COMMAND =>
        assert(
          command.isDefined,
          "A command is required when using the COMMAND health check protocol."
        )
        MesosProtos.HealthCheck.newBuilder
          .setCommand(this.command.get.toProto)

      case Protocol.HTTP =>
        throw new UnsupportedOperationException(
          s"Mesos does not support health checks of type [$protocol]")

      case _ =>
        throw new UnsupportedOperationException(
          s"Mesos does not support health checks of type [$protocol]")
    }

    builder.setDelaySeconds(0)
      .setIntervalSeconds(this.interval.toSeconds.toDouble)
      .setTimeoutSeconds(this.timeout.toSeconds.toDouble)
      .setConsecutiveFailures(this.maxConsecutiveFailures)
      .setGracePeriodSeconds(this.gracePeriod.toUnit(SECONDS))
      .build
  }

  override def version: Timestamp = Timestamp.zero
}

object HealthCheck {
  val DefaultPath = Some("/")
  val DefaultProtocol = Protocol.HTTP
  val DefaultPortIndex = 0
  val DefaultCommand = None
  // Dockers can take a long time to download, so default to a fairly long wait.
  val DefaultGracePeriod = 5.minutes
  val DefaultInterval = 1.minute
  val DefaultTimeout = 20.seconds
  val DefaultMaxConsecutiveFailures = 3
  val DefaultIgnoreHttp1xx = false
}
