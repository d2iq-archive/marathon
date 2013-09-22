package mesosphere.marathon

import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import org.junit.Test
import org.apache.mesos.state.InMemoryState
import mesosphere.marathon.Protos.MarathonTask
import org.apache.mesos.Protos.Value.Text
import org.apache.mesos.Protos.{TaskID, Attribute}
import java.io.{ByteArrayInputStream, ObjectInputStream, ByteArrayOutputStream, ObjectOutputStream}

import org.junit.Assert._
import mesosphere.marathon.tasks.{TaskTracker, TaskIDUtil}
import com.google.common.collect.Lists

class TaskTrackerTest extends AssertionsForJUnit with MockitoSugar {

  def makeSampleTask(id: String) = {
    MarathonTask.newBuilder()
      .setHost("host")
      .addAllPorts(Lists.newArrayList(999))
      .setId(id)
      .addAttributes(
        Attribute.newBuilder()
        .setName("attr1")
        .setText(Text.newBuilder()
        .setValue("bar"))
        .setType(org.apache.mesos.Protos.Value.Type.TEXT)
        .build())
      .build()
  }

  def makeTask(id: String, host: String, port: Int) = {
    MarathonTask.newBuilder()
      .setHost(host)
      .addAllPorts(Lists.newArrayList(port))
      .setId(id)
      .addAttributes(
      Attribute.newBuilder()
        .setName("attr1")
        .setText(Text.newBuilder()
        .setValue("bar"))
        .setType(org.apache.mesos.Protos.Value.Type.TEXT)
        .build())
      .build()
  }

  @Test
  def testStartingPersistsTasks() {
    val state = new InMemoryState
    val taskTracker = new TaskTracker(state)
    val app = "foo"
    val taskId1 = TaskIDUtil.taskId(app, 1)
    val taskId2 = TaskIDUtil.taskId(app, 2)
    val taskId3 = TaskIDUtil.taskId(app, 3)

    val task1 = makeTask(taskId1, "foo.bar.bam", 100)
    val task2 = makeTask(taskId2, "foo.bar.bam", 200)
    val task3 = makeTask(taskId3, "foo.bar.bam", 300)

    taskTracker.starting(app, task1)
    taskTracker.running(app, TaskID.newBuilder().setValue(taskId1).build)

    taskTracker.starting(app, task2)
    taskTracker.running(app, TaskID.newBuilder().setValue(taskId2).build)

    taskTracker.starting(app, task3)
    taskTracker.running(app, TaskID.newBuilder().setValue(taskId3).build)

    val results = taskTracker.fetch(app)

    assertTrue(results.contains(task1))
    assertTrue(results.contains(task2))
    assertTrue(results.contains(task3))
  }

  @Test
  def testSerDe() {
    val state = new InMemoryState
    val taskTracker = new TaskTracker(state)

    val task1 = makeSampleTask("task1")
    val task2 = makeSampleTask("task2")
    val task3 = makeSampleTask("task3")

    val baos = new ByteArrayOutputStream()
    baos.flush()
    val oos = new ObjectOutputStream(baos)
    taskTracker.serialize(Set(task1, task2, task3), oos)

    val ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
    val tasks = taskTracker.deserialize(ois)
    assertTrue("Task1 is not properly serialized", tasks.contains(task1))
    assertTrue("Task2 is not properly serialized", tasks.contains(task2))
    assertTrue("Task3 is not properly serialized", tasks.contains(task2))
  }
}
