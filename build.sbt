enablePlugins(JavaAppPackaging)

name := "akka-http-nifi"
organization := "org.example"
version := "1.0"
scalaVersion := "2.12.3"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val akkaV       = "2.5.3"
  val akkaHttpV   = "10.0.9"
  val scalaTestV  = "3.0.1"
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-testkit" % akkaV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-xml" % akkaHttpV,
    "com.sun.jersey" % "jersey-json" % "1.19.4",
    "io.spray" %%  "spray-json" % "1.3.3",
    "org.apache.nifi" % "nifi-client-dto" % "1.3.0",
    "org.scalatest"     %% "scalatest" % scalaTestV % "test"
  )
}

//Do this if you want to use sbt to run our Akka-http service
Keys.mainClass in (Compile) := Some("NifiServiceImpl")
////Do this if you want to invoke sbt through a script, giving it a json file of instructions, like so:
//sbt " run sampleInputs/sample1.json"
//Keys.mainClass in (Compile) := Some("TheScript")

//These depend on having addSbtPlugin("io.spray" % "sbt-revolver" % "0.8.0") in plugins.sbt
//I like the command ~re-start, so I keep it.
Revolver.settings
