package mesosphere.marathon.core.volume.providers

import com.wix.accord._
import com.wix.accord.dsl._
import com.wix.accord.Validator
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{ ContainerInfo, Volume => MesosVolume, Environment }
import scala.collection.JavaConverters._

/**
  * DVDIProvider (Docker Volume Driver Interface provider) handles persistent volumes allocated
  * by a specific docker volume driver plugin. This works for both docker and mesos containerizers,
  * albeit with some limitations:
  *   - only a single volume driver per container is allowed when using the docker containerizer
  *   - docker containerizer requires that referenced volumes be created prior to application launch
  *   - mesos containerizer only supports volumes mounted in RW mode
  */
protected case object DVDIProvider extends InjectionHelper[PersistentVolume] with PersistentVolumeProvider {
  import org.apache.mesos.Protos.Volume.Mode

  override val name = Some("dvdi")

  val optionDriver = name.get + "/driverName"
  val optionIOPS = name.get + "/iops"
  val optionType = name.get + "/volumeType"

  val validOptions: Validator[Map[String, String]] = validator[Map[String, String]] { opt =>
    opt.get(optionDriver) as "driverName option" is notEmpty
    // TODO(jdef) stronger validation for contents of driver name
    opt.get(optionDriver).each as "driverName option" is notEmpty
    // TODO(jdef) validate contents of iops and volume type options
  }

  val validPersistentVolume = validator[PersistentVolume] { v =>
    v.persistent.name is notEmpty
    v.persistent.name.each is notEmpty
    v.persistent.providerName is notEmpty
    v.persistent.providerName.each is notEmpty
    v.persistent.providerName.each is equalTo(name.get) // sanity check
    v.persistent.options is valid(validOptions)
  }

  def nameOf(vol: PersistentVolumeInfo): Option[String] = {
    if (vol.providerName.isDefined && vol.name.isDefined) {
      Some(vol.providerName.get + "::" + vol.name.get)
    }
    else None
  }

  private def getInstanceViolations(app: AppDefinition) = {
    if (app.container.isDefined &&
        DVDIProvider.this.collect(app.container.get).nonEmpty &&
        app.instances > 1)
      Some(RuleViolation(app.id,
          s"Number of instances is limited to 1 when declaring external volumes in app ${app.id}", None))
    else None
  }

  // group-level validation for DVDI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  val groupValidation: Validator[Group] = new Validator[Group] {
    override def apply(g: Group): Result = {
      val groupViolations = g.apps.flatMap { app =>
        val nameCounts = volumeNameCounts(app)
        val internalNameViolations = {
          nameCounts.filter(_._2 > 1).map{ e =>
            RuleViolation(app.id, s"Requested volume ${e._1} is declared more than once within app ${app.id}", None)
          }
        }
        val instanceViolations = getInstanceViolations(app)
        val ruleViolations = app.container.toSet[Container].flatMap(DVDIProvider.this.collect).flatMap{ vol =>
          val name = nameOf(vol.persistent)
          if (name.isDefined) {
            for {
              otherApp <- g.transitiveApps.toList
              if otherApp.id != app.id // do not compare to self
              otherVol <- otherApp.container.toSet[Container].flatMap(DVDIProvider.this.collect)
              otherName <- nameOf(otherVol.persistent)
              if name.get == otherName
            } yield RuleViolation(app.id,
              s"Requested volume $name conflicts with a volume in app ${otherApp.id}", None)
          }
          else None
        }
        if (internalNameViolations.isEmpty && ruleViolations.isEmpty && instancesViolation.isEmpty) None
        else Some(GroupViolation(app, "app contains conflicting volumes", None,
          internalNameViolations.toSet[Violation] ++ instancesViolation.toSet ++ ruleViolations.toSet))
      }
      if (groupViolations.isEmpty) Success
      else Failure(groupViolations.toSet)
    }
  }

  def driversInUse(ct: Container): Set[String] =
    DVDIProvider.this.collect(ct).flatMap(_.persistent.options.get(optionDriver)).toSet

  /** @return a count of volume references-by-name within an app spec */
  def volumeNameCounts(app: AppDefinition): Map[String, Int] =
    app.container.toSet[Container].flatMap(DVDIProvider.this.collect).flatMap{ pv => nameOf(pv.persistent) }.
      groupBy(identity).mapValues(_.size)

  protected[providers] def modes(ct: Container): Set[Mode] =
    DVDIProvider.this.collect(ct).map(_.mode).toSet

  /** Only allow a single docker volume driver to be specified w/ the docker containerizer. */
  val containerValidation: Validator[Container] = validator[Container] { ct =>
    (ct.`type` is equalTo(ContainerInfo.Type.MESOS) and (modes(ct).each is equalTo(Mode.RW))) or (
      (ct.`type` is equalTo(ContainerInfo.Type.DOCKER)) and (driversInUse(ct).size should equalTo(1))
    )
  }

  /** non-agent-local PersistentVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: PersistentVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.persistent.name.get) // validation should protect us from crashing here since name is req'd
      .setMode(volume.mode)
      .build

  override def injectContainer(ctx: ContainerContext, pv: PersistentVolume): ContainerContext = {
    // special behavior for docker vs. mesos containers
    // - docker containerizer: serialize volumes into mesos proto
    // - docker containerizer: specify "volumeDriver" for the container
    val container = ctx.container // TODO(jdef) clone?
    if (container.getType == ContainerInfo.Type.DOCKER && container.hasDocker) {
      val driverName = pv.persistent.options(optionDriver)
      if (container.getDocker.getVolumeDriver != driverName) {
        container.setDocker(container.getDocker.toBuilder.setVolumeDriver(driverName).build)
      }
      ContainerContext(container.addVolumes(toMesosVolume(pv)))
    }
    super.injectContainer(ctx, pv)
  }

  override def injectCommand(ctx: CommandContext, pv: PersistentVolume): CommandContext = {
    // special behavior for docker vs. mesos containers
    // - mesos containerizer: serialize volumes into envvar sets
    val (containerType, command) = (ctx.containerType, ctx.command) // TODO(jdef) clone command?
    if (containerType == ContainerInfo.Type.MESOS) {
      val env = if (command.hasEnvironment) command.getEnvironment.toBuilder else Environment.newBuilder
      val toAdd = volumeToEnv(pv, env.getVariablesList.asScala)
      env.addAllVariables(toAdd.asJava)
      CommandContext(containerType, command.setEnvironment(env.build))
    }
    super.injectCommand(ctx, pv)
  }

  val dvdiVolumeName = "DVDI_VOLUME_NAME"
  val dvdiVolumeDriver = "DVDI_VOLUME_DRIVER"
  val dvdiVolumeOpts = "DVDI_VOLUME_OPTS"

  def volumeToEnv(v: PersistentVolume, i: Iterable[Environment.Variable]): Seq[Environment.Variable] = {
    val offset = i.filter(_.getName.startsWith(dvdiVolumeName)).map{ s =>
      val ss = s.getName.substring(dvdiVolumeName.size)
      if (ss.length > 0) ss.toInt else 0
    }.foldLeft(-1)((z, i) => if (i > z) i else z)
    val suffix = if (offset >= 0) (offset + 1).toString else ""

    def newVar(name: String, value: String): Environment.Variable =
      Environment.Variable.newBuilder.setName(name).setValue(value).build

    Seq(
      newVar(dvdiVolumeName + suffix, v.persistent.name.get),
      newVar(dvdiVolumeDriver + suffix, v.persistent.options(optionDriver))
    // TODO(jdef) support other options here
    )
  }
}
