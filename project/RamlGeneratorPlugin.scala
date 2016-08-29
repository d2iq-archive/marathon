package mesosphere.raml

import sbt._
import sbt.Keys._
import org.raml.v2.api.RamlModelBuilder
import scala.collection.JavaConversions._

object RamlGeneratorPlugin extends AutoPlugin {
  object autoImport {
    lazy val ramlFiles = settingKey[Seq[File]]("List of RAML 1.0 top level definitions togenerate from")
    lazy val ramlPackage = settingKey[String]("Package to place all generated classes in")
    lazy val ramlGenerate = taskKey[Seq[File]]("Generate the RAML files")
  }
  import autoImport._
  override lazy val projectSettings = inConfig(Compile)(Seq(
    ramlFiles in Compile := Seq(baseDirectory.value / "docs" / "docs" / "rest-api" / "public" / "api" / "v2" / "pods.raml"),
    ramlPackage in Compile := "mesosphere.marathon.raml",
    ramlGenerate in Compile := {
      generate(ramlFiles.value, ramlPackage.value, sourceManaged.value, streams.value.log)
    },
    sourceGenerators in Compile <+= ramlGenerate
  ))

  def generate(ramlFiles: Seq[File], pkg: String, outputDir: File, log: Logger): Seq[File] = {
    val models = ramlFiles.map { file =>
      val model = new RamlModelBuilder().buildApi(file)
      if (model.hasErrors) {
        model.getValidationResults.foreach { error =>
          sys.error(error.toString)
        }
      }
      model
    }
    val types = RamlTypeGenerator.generateTypes(pkg, models.flatMap(m => m.getApiV10.types().toVector)(collection.breakOut))
    types.map { case (typeName, content) =>
      val file = outputDir / pkg / s"$typeName.scala"
      IO.write(file, treehugger.forest.treeToString(content))
      file
    }(collection.breakOut)
  }
}