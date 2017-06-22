name := "geoscala"
organization := "com.outr"
version := "1.0.5"
scalaVersion := "2.12.2"
crossScalaVersions := List("2.12.2", "2.11.11")
parallelExecution in Test := false
fork := true
scalacOptions ++= Seq("-unchecked", "-deprecation")
resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies += "com.outr" %% "lucene4s" % "1.4.6"
libraryDependencies += "com.outr" %% "scribe" % "1.4.3"
libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.3" % "test"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % "test"

testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oF")