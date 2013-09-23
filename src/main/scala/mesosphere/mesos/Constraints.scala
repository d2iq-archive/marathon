package mesosphere.mesos


import scala.collection.JavaConverters._
import mesosphere.marathon.Protos.Constraint

object Constraints {

  def meetsConstraint(tasks: Set[mesosphere.marathon.Protos.MarathonTask],
                      attributes: Set[org.apache.mesos.Protos.Attribute],
                      field: String,
                      op: Int,
                      value : Option[String]): Boolean = {

    //TODO(*): Implement LIKE (use value for this)

    if (tasks.isEmpty) {
      //TODO(*)  This is a bit suboptimal as we're just accepting the first slot
      //         that fulfills, e.g. a cluster constraint. However, for cluster
      //         to ensure placing N instances, we should select the largest offer
      //         first. (This is a optimization).
      true
    } else {
      val attr = attributes.filter(_.getName == field).headOption

      if (attr.nonEmpty) {
        val matches = matchTasks(tasks, field, attr.get.getText.getValue)
        op match {
          case Constraint.Operator.UNIQUE_VALUE => matches.isEmpty
          case Constraint.Operator.CLUSTER_VALUE => matches.size == tasks.size
        }
      } else {
        // This will be reached in case we want to schedule for a rack_id but it
        // is never supplied.
        false
      }
    }
  }

  def matchLike(attr: Set[org.apache.mesos.Protos.Attribute],
                field: String,
                regex: String): Boolean = {

    attr
      .filter(x =>
                x.getName == field && x.getText.getValue.matches(regex))
      .nonEmpty
  }

  /**
   * Filters running tasks by matching their attributes to this field & value.
   * @param tasks
   * @param field
   * @param value
   * @return
   */
  private def matchTasks(tasks: Iterable[mesosphere.marathon.Protos.MarathonTask],
                         field: String,
                         value: String) = {
    tasks
      .filter(x =>
      (x.getAttributesList.asScala)
        .filter(y => {
          y.getName == field &&
          y.getText.getValue == value})
        .nonEmpty)
  }
}
