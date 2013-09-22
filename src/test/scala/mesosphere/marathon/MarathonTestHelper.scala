package mesosphere.marathon

import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Value.{Text, Ranges}
import mesosphere.mesos.TaskBuilder
import mesosphere.marathon.api.v1.AppDefinition
import scala.collection.JavaConverters._

/**
 * @author Tobi Knaup
 */

trait MarathonTestHelper {

  def makeBasicOffer(cpus: Double, mem: Double,
                     beginPort: Int, endPort: Int) = {
    val portsResource = makePortsResource(Seq((beginPort, endPort)))
    val cpusResource = TaskBuilder.scalarResource("cpus", cpus)
    val memResource = TaskBuilder.scalarResource("mem", mem)
    Offer.newBuilder
      .setId(OfferID.newBuilder.setValue("1"))
      .setFrameworkId(FrameworkID.newBuilder.setValue("marathon"))
      .setSlaveId(SlaveID.newBuilder.setValue("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(portsResource)
  }

  def makePortsResource(ranges: Seq[(Int, Int)]) = {
    val rangeProtos = ranges.map(r => {
      Value.Range.newBuilder
        .setBegin(r._1)
        .setEnd(r._2)
        .build
    })

    val rangesProto = Ranges.newBuilder
      .addAllRange(rangeProtos.asJava)
      .build
    Resource.newBuilder
      .setName(TaskBuilder.portsResourceName)
      .setType(Value.Type.RANGES)
      .setRanges(rangesProto)
      .build
  }

  def makeBasicApp() = {
    val app = new AppDefinition
    app.id = "testApp"
    app.cpus = 1
    app.mem = 64
    app.executor = "//cmd"
    app
  }

  def makeAttribute(attr: String, attrVal: String) = {
    Attribute.newBuilder()
      .setName(attr)
      .setText(Text.newBuilder()
      .setValue(attrVal))
      .setType(org.apache.mesos.Protos.Value.Type.TEXT)
      .build()
  }
}