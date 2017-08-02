name := "geoscala"
organization := "com.outr"
version := "1.0.6"
scalaVersion := "2.12.3"
crossScalaVersions := List("2.12.3", "2.11.11")
parallelExecution in Test := false
fork := true
scalacOptions ++= Seq("-unchecked", "-deprecation")
resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies += "com.outr" %% "lucene4s" % "1.4.7"
libraryDependencies += "com.outr" %% "scribe" % "1.4.5"
libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.3" % "test"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % "test"

testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oF")