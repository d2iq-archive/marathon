package mesosphere.marathon

import com.typesafe.scalalogging.StrictLogging

object DeprecatedFeatures extends StrictLogging {
  case class DeprecatedFeature(
      key: String,
      description: String,
      warnVersion: SemVer,
      removeVersion: SemVer
  )

  val syncProxy = DeprecatedFeature(
    "sync_proxy",
    description = "Old, blocking IO implementation for leader proxy used by Marathon standby instances.",
    warnVersion = SemVer(1, 7, 0),
    removeVersion = SemVer(1, 8, 0))

  def all = Seq(syncProxy)

  /**
    * Provided a list of enabled deprecated features, output appropriate log messages based on current version and
    * deprecation / removal versions.
    *
    * @param deprecatedfeature The deprecated features specified to enable
    * @param currentVersion The current version of Marathon
    * @return Boolean true if all specified deprecatedFeatures are still allowed in the current version of Marathon
    */
  def allDeprecatedFeaturesActive(
    deprecatedFeatures: Iterable[DeprecatedFeature],
    currentVersion: SemVer = BuildInfo.version): Boolean = {
    var success = true

    deprecatedFeatures.foreach { df =>
      if (currentVersion >= df.removeVersion) {
        success = false
        logger.error(s"${df.key} has been removed as of ${df.removeVersion}. You should migrate back to a previous " +
          "version of Marathon, remove the deprecated feature flag, ensure that your cluster continues to work, and " +
          "then upgrade again.\n\n" +
          "If you are confident that you no longer need the deprecated feature flag, you can simply remove it.")
      } else if (currentVersion >= df.warnVersion) {
        logger.warn(s"Deprecated feature ${df.key} is scheduled to be removed in ${df.removeVersion}. You should " +
          "remove the deprecated feature flag as soon possible.")
      }
    }
    success
  }
}
