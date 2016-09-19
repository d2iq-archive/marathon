package mesosphere.marathon.instance

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId._
import org.scalatest.{ FunSuite, Matchers }

class InstanceIdTest extends FunSuite with Matchers {

  test("AppIds can be converted to InstanceIds and back to AppIds") {
    val appId = "/test/foo/bla/rest".toPath
    val instanceId = Instance.Id.forRunSpec(appId)
    instanceId.runSpecId should equal(appId)
  }

  test("InstanceIds can be converted to TaskIds without container name") {
    val appId = "/test/foo/bla/rest".toPath
    val instanceId = Instance.Id.forRunSpec(appId)
    val taskId = Task.Id.forInstanceId(instanceId, container = None)
    taskId.idString should be(instanceId.idString + ".container")
  }

  test("InstanceIds can be converted to TaskIds with container name") {
    val appId = "/test/foo/bla/rest".toPath
    val instanceId = Instance.Id.forRunSpec(appId)
    val container = MesosContainer("firstOne", resources = Resources())
    val taskId = Task.Id.forInstanceId(instanceId, Some(container))
    taskId.idString should be(instanceId.idString + ".firstOne")
  }

  test("InstanceIds should be created by static string") {
    val idString = "app.marathon-task"
    val instanceId = Instance.Id(idString)
    instanceId.idString should be(idString)
    instanceId.runSpecId.safePath should be("app")
    val taskId = Task.Id(idString + ".app")
    taskId.instanceId should be(instanceId)
  }

}
