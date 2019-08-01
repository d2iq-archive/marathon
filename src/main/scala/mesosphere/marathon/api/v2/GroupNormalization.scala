package mesosphere.marathon
package api.v2

import mesosphere.marathon.raml.{AppVisitor, GroupUpdate, GroupUpdateVisitor}
import mesosphere.marathon.state.{AbsolutePathId, PathId, RootGroup}
import mesosphere.mesos.ResourceMatcher.Role

import scala.annotation.tailrec

/**
  * Visitor that normalizes a root group update.
  *
  * @param conf The [[MarathonConf]].
  */
case class RootGroupVisitor(conf: MarathonConf) extends GroupUpdateVisitor {

  override def visit(thisGroup: GroupUpdate): GroupUpdate = thisGroup.copy(enforceRole = Some(false))

  override def childGroupVisitor(): GroupUpdateVisitor = TopLevelGroupVisitor(conf)

  override def appVisitor(): AppVisitor = AppNormalizeVisitor(conf, conf.mesosRole())
}

/**
  * Visitor that normalizes a top-level group update, ie an update for a group directly under root eg `/prod`.
  *
  * @param conf The [[MarathonConf]].
  */
case class TopLevelGroupVisitor(conf: MarathonConf) extends GroupUpdateVisitor {
  var defaultRole: Role = conf.mesosRole()

  override def visit(thisGroup: raml.GroupUpdate): raml.GroupUpdate = {
    val enforceRole = thisGroup.enforceRole.getOrElse {
      conf.groupRoleBehavior() match {
        case GroupRoleBehavior.Off => false
        case GroupRoleBehavior.Top => true
      }
    }
    if (enforceRole) defaultRole = PathId(thisGroup.id.get).root

    thisGroup.copy(enforceRole = Some(enforceRole))
  }

  override def childGroupVisitor(): GroupUpdateVisitor = ChildGroupVisitor(conf, defaultRole)

  override def appVisitor(): AppVisitor = AppNormalizeVisitor(conf, defaultRole)
}

/**
  * Visitor that normalizes an update for a group that is not root or a top-level group. See
  * [[RootGroupVisitor]] and [[TopLevelGroupVisitor]] for these cases.
  *
  * @param conf The [[MarathonConf]].
  * @param defaultRole The default Mesos role for all apps in this group.
  */
case class ChildGroupVisitor(conf: MarathonConf, defaultRole: Role) extends GroupUpdateVisitor {

  override def visit(thisGroup: GroupUpdate): GroupUpdate = {
    if (thisGroup.enforceRole.isEmpty) thisGroup.copy(enforceRole = Some(false))
    else thisGroup
  }

  override val childGroupVisitor: GroupUpdateVisitor = this

  override val appVisitor: AppVisitor = AppNormalizeVisitor(conf, defaultRole)
}

/**
  * Visitor that normalizes an [[raml.App]] in an [[raml.GroupUpdate]].
  *
  * @param conf The [[MarathonConf]].
  * @param defaultRole The default Mesos role of the app.
  */
case class AppNormalizeVisitor(conf: MarathonConf, defaultRole: Role) extends AppVisitor {

  val normalizationConfig = AppNormalization.Configuration(conf, defaultRole)

  override def visit(app: raml.App, absoluteGroupPath: AbsolutePathId): raml.App = {
    val validateAndNormalizeApp: Normalization[raml.App] = AppHelpers.appNormalization(normalizationConfig)(AppNormalization.withCanonizedIds(absoluteGroupPath))
    val normalizedAbsoluteId = PathId(app.id).canonicalPath(absoluteGroupPath).toString

    validateAndNormalizeApp.normalized(app.copy(id = normalizedAbsoluteId))
  }
}

object GroupNormalization {

  /**
    * Dispatch the visitor on the group update and its children.
    *
    * @param conf The [[MarathonConf]]
    * @param groupUpdate The group update that will be visited.
    * @param base The absolute path of group being updated.
    * @param visitor
    * @return The group update returned by the visitor.
    */
  private def dispatch(conf: MarathonConf, groupUpdate: raml.GroupUpdate, base: AbsolutePathId, visitor: GroupUpdateVisitor): raml.GroupUpdate = {
    val updatedGroup = visitor.visit(groupUpdate)

    // Visit each child group.
    val childGroupVisitor = visitor.childGroupVisitor()
    val children = groupUpdate.groups.map(_.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(base)
      dispatch(conf, childGroup, absoluteChildGroupPath, childGroupVisitor)
    })

    // Visit each app.
    val appVisitor = visitor.appVisitor()
    val apps = groupUpdate.apps.map(_.map { app =>
      appVisitor.visit(app, base)
    })

    updatedGroup.copy(groups = children, apps = apps)
  }

  def partialUpdateNormalization(conf: MarathonConf): Normalization[raml.GroupPartialUpdate] = Normalization { update =>
    update.copy(enforceRole = Some(effectiveEnforceRole(conf, update.enforceRole)))
  }

  /**
    * Normalize the group update of an API call.
    *
    * @param conf The [[MarathonConf]] holding the default Mesos role and the default enforce group
    *             role behavior.
    * @param base The absolute path of the group being updated.
    * @param originalRootGroup The [[RootGroup]] before the update was applied.
    * @return The normalized group update.
    */
  def updateNormalization(conf: MarathonConf, base: AbsolutePathId, originalRootGroup: RootGroup): Normalization[raml.GroupUpdate] = Normalization { update =>
    // Only update if this is not a scale or rollback
    if (update.version.isEmpty && update.scaleBy.isEmpty) {
      if (base.isRoot) dispatch(conf, update, base, RootGroupVisitor(conf))
      else if (base.isTopLevel) dispatch(conf, update, base, TopLevelGroupVisitor(conf))
      else {
        val defaultRole = inferDefaultRole(conf, base, originalRootGroup)
        dispatch(conf, update, base, ChildGroupVisitor(conf, defaultRole))
      }
    } else update
  }

  /**
    * Infers the enforce role field for a top-level group based on the update value and the default behavior.
    *
    * @param conf The Marathon conf defining the default behavior.
    * @param maybeEnforceRole The role defined by the updated.
    * @return Whether or not to enforce the role.
    */
  private def effectiveEnforceRole(conf: MarathonConf, maybeEnforceRole: Option[Boolean]): Boolean = {
    maybeEnforceRole.getOrElse {
      conf.groupRoleBehavior() match {
        case GroupRoleBehavior.Off => false
        case GroupRoleBehavior.Top => true
      }
    }
  }

  /**
    * Determine the default role for a lower level group.
    *
    * @param conf The [[MarathonConf]] used to check the default Mesos role.
    * @param groupId The group id of the lower level group. Must not be root or top-level.
    * @param rootGroup The root group used to look up the default role.
    * @return The default role for all apps and pods.
    */
  @tailrec private def inferDefaultRole(conf: MarathonConf, groupId: AbsolutePathId, rootGroup: RootGroup): Role = {
    assert(!groupId.isTopLevel && !groupId.isRoot)
    if (groupId.parent.isTopLevel) {
      rootGroup.group(groupId.parent).fold(conf.mesosRole()) { parentGroup =>
        if (parentGroup.enforceRole) groupId.parent.root else conf.mesosRole()
      }
    } else inferDefaultRole(conf, groupId.parent, rootGroup)
  }
}
