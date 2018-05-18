#!/usr/bin/env amm

import $ivy.`com.typesafe.play::play-json:2.6.0`

import scalaj.http._
import ammonite.ops.ImplicitWd._
import ammonite.ops._
import play.api.libs.json.{JsObject, Json, JsString}
import $file.githubClient
import $file.awsClient
import $file.upgrade

@main
def bumpMarathonInDcos(requestedVersion: String): Unit = {
  val version = if (requestedVersion == "latest") {
    val latestMarathonVersion = %%("./version").out.string.trim
    val versionGitHash = %%("./version", "commit").out.string.trim
    s"$latestMarathonVersion-$versionGitHash"
  } else {
    requestedVersion
  }

  val (packagePath, packageSha1) = getS3PackageInformation(version)

  // updates package json and commits the result to a branch in mesosphere/dcos
  upgrade.updateDcosService(packagePath, packageSha1, s"Bumping Marathon to $version", "marathon", s"autobump-$version")

  // TODO create PR in dcos/dcos
  // TODO make the PR as "ready for review", bump-ee
  // TODO ping on slack? (before we have auto-approvals)
}

private def getS3PackageInformation(version: String): (String, String) = {
  val s3Path = awsClient.s3PathFor(s"builds/$version/marathon-$version.tgz")
  val s3Artifact = awsClient.Artifact(s3Path, null)

  if (!packageExists(s3Artifact.downloadUrl)) {
    println(s"Package on path '${s3Artifact.downloadUrl}' does not exist.")
    sys.exit(1)
    ???
  }
  val packageSha1 = getPackageSha1(s3Artifact.downloadUrl)

  (s3Artifact.downloadUrl, packageSha1)
}

private def packageExists(packageDownloadPath: String): Boolean = {
  Http(packageDownloadPath)
    .method("HEAD")
    .timeout(connTimeoutMs = 5000, readTimeoutMs = 100000)
    .asString
    .code == 200
}

private def getPackageSha1(packageDownloadPath: String): String = {
  Http(packageDownloadPath + ".sha1")
    .method("HEAD")
    .timeout(connTimeoutMs = 5000, readTimeoutMs = 100000)
    .asString
    .throwError
    .body
}


private def getMarathonPackageJson(): String = {
  val githubUsername = githubClient.githubUsername()

  Http(s"https://raw.githubusercontent.com/$githubUsername/dcos/master/packages/marathon/buildinfo.json")
    .timeout(connTimeoutMs = 5000, readTimeoutMs = 100000)
    .asString
    .throwError
    .body
}