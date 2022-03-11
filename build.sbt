name := "storage"

version := "0.1.0-pre.1"

scalaVersion := "3.1.1"

scalacOptions ++=
  Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-language:postfixOps",
    "-language:implicitConversions",
    "-language:existentials",
    "-language:dynamics",
  )

organization := "io.github.edadma"

githubOwner := "edadma"

githubRepository := name.value

mainClass := Some(s"${organization.value}.${name.value}.Main")

licenses := Seq("ISC" -> url("https://opensource.org/licenses/ISC"))

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.11" % "test"

libraryDependencies ++= Seq(
  "com.lihaoyi" %% "pprint" % "0.7.1" % "test",
)
