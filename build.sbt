name := "geoscala"
organization := "com.outr"
version := "1.0.7-SNAPSHOT"
scalaVersion := "2.12.5"
crossScalaVersions := List("2.12.5", "2.11.12")
parallelExecution in Test := false
fork := true
scalacOptions ++= Seq("-unchecked", "-deprecation")
resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.sonatypeRepo("snapshots")

publishTo in ThisBuild := sonatypePublishTo.value
sonatypeProfileName in ThisBuild := "com.outr"
publishMavenStyle in ThisBuild := true
licenses in ThisBuild := Seq("MIT" -> url("https://github.com/outr/geoscala/blob/master/LICENSE"))
sonatypeProjectHosting in ThisBuild := Some(xerial.sbt.Sonatype.GitHubHosting("outr", "geoscala", "matt@outr.com"))
homepage in ThisBuild := Some(url("https://github.com/outr/geoscala"))
scmInfo in ThisBuild := Some(
  ScmInfo(
    url("https://github.com/outr/geoscala"),
    "scm:git@github.com:outr/geoscala.git"
  )
)
developers in ThisBuild := List(
  Developer(id="darkfrog", name="Matt Hicks", email="matt@matthicks.com", url=url("http://matthicks.com"))
)

libraryDependencies += "com.outr" %% "lucene4s" % "1.6.0"
libraryDependencies += "com.outr" %% "scribe" % "2.3.2"
libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.5" % "test"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.5" % "test"

testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oF")