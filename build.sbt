name := "geoscala"
organization := "com.outr"
version := "1.0.4"
scalaVersion := "2.12.1"
crossScalaVersions := List("2.12.1", "2.11.8")
sbtVersion := "0.13.13"
parallelExecution in Test := false
fork := true
scalacOptions ++= Seq("-unchecked", "-deprecation")
resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies += "com.outr" %% "lucene4s" % "1.4.6"
libraryDependencies += "com.outr" %% "scribe" % "1.4.2"
libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.1" % "test"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % "test"

testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oF")