#!/usr/bin/env amm

import $ivy.`com.typesafe.play::play-json:2.6.0`

import scalaj.http._
import play.api.libs.json.Json

val minorVersions = Seq(9, 10, 11)

case class ContentResponse(download_url: String)
implicit val contentResponseFormat = Json.format[ContentResponse]

case class PackageJson(single_source: SourceDefinition)
case class SourceDefinition(url: Option[String], ref: Option[String], git: Option[String])
implicit val sourceDefinitionFormat = Json.format[SourceDefinition]
implicit val packageJsonFormat = Json.format[PackageJson]

case class PackageUrl(url: String, packageName: String) {
  lazy val packageVersion = {
    // e.g. metronome-0.5.0.tgz
    url.split('/').last.replace(".tgz", "").replace(s"$packageName-", "")
  }
}

/***
  * Find out which version of a package (Marathon/Metronome/Mesos) is in which version of DC/OS
  * example usage:
  * ./dcos-versions.sh marathon
  * ./dcos-versions.sh metronome
  * ./dcos-versions.sh mesos
  */
@main
def getDcosVersions(packageName: String): Unit = {
  minorVersions.foreach(minorVersion => {
    var patchVersion = 0
    var dcosVersionExists = true
    while (dcosVersionExists) {
      val dcosVersion = s"1.$minorVersion.$patchVersion"
      try {
        val packageVersion = getPackageVersion(dcosVersion, packageName)
        println(s"$dcosVersion - $packageVersion")
      } catch {
        case _: VersionDoesNotExistException =>
          dcosVersionExists = false
      }

      patchVersion += 1
    }

    val dcosVersion = s"1.$minorVersion"
    val vNextVersion = getPackageVersion(dcosVersion, packageName)
    println(s"$dcosVersion.next - $vNextVersion")
  })

  val masterVersion = getPackageVersion("master", packageName)
  println(s"master - $masterVersion")
}

class VersionDoesNotExistException() extends Exception

def getPackageVersion(dcosVersion: String, packageName: String): String = {
  val response = Http(s"https://raw.githubusercontent.com/dcos/dcos/$dcosVersion/packages/$packageName/buildinfo.json")
    .timeout(connTimeoutMs = 5000, readTimeoutMs = 100000)
    .asString
  if (response.code == 404) {
    throw new VersionDoesNotExistException
  }
  if (response.code != 200) {
    throw new Exception(s"HTTP code ${response.code} from github with response ${response.body}")
  }
  // e.g. https://s3.amazonaws.com/downloads.mesosphere.io/metronome/releases/0.5.0/metronome-0.5.0.tgz
  val parsedJsonSource = Json.parse(response.body).as[PackageJson].single_source
  if (parsedJsonSource.url.isDefined) {
    val packageUrl = PackageUrl(parsedJsonSource.url.get, packageName)
    packageUrl.packageVersion
  } else {
    s"${parsedJsonSource.ref.get} (${parsedJsonSource.git.get})"
  }
}