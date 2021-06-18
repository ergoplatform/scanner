import sbt.ExclusionRule
import sbt.Keys.fullClasspath

name := "scanner"

version := "0.2"
organization := "org.ergoplatform"
scalaVersion := "2.12.7"

lazy val `scanner` = (project in file(".")).enablePlugins(PlayScala)

resolvers ++= Seq("Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
  "Bintray" at "https://jcenter.bintray.com/", //for org.ethereum % leveldbjni-all
  "SonaType" at "https://oss.sonatype.org/content/groups/public",
  "Typesafe maven releases" at "https://dl.bintray.com/typesafe/maven-releases/",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/")

val circeVersion = "0.12.3"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

assembly / mainClass := Some("play.core.server.ProdServerStart")
assembly / fullClasspath += Attributed.blank(PlayKeys.playPackageAssets.value)


libraryDependencies ++= Seq(ehcache, ws, specs2 % Test, guice)
libraryDependencies ++= Seq(
  ("org.ergoplatform" %% "ergo" % "v4.0.13-5251a78b-SNAPSHOT")
    .excludeAll(
      ExclusionRule(organization = "com.typesafe.akka"),
      ExclusionRule(organization = "ch.qos.logback"),
      ExclusionRule(organization = "org.ethereum"),
      ExclusionRule(organization = "javax.xml.bind"),
    ).force(),
  "com.h2database" % "h2" % "1.4.200",
  "com.typesafe.play" %% "play-slick" % "4.0.0",
  "com.typesafe.play" %% "play-slick-evolutions" % "4.0.0",

  "org.scalaj" %% "scalaj-http" % "2.3.0",

  "com.github.pureconfig" %% "pureconfig" % "0.16.0"
)


assembly / assemblyJarName := s"${name.value}-${version.value}.jar"

assembly / assemblyMergeStrategy := {
  case "logback.xml" => MergeStrategy.first
  case PathList("reference.conf") => MergeStrategy.concat
  case manifest if manifest.contains("MANIFEST.MF") => MergeStrategy.discard
  case manifest if manifest.contains("module-info.class") => MergeStrategy.discard
  case referenceOverrides if referenceOverrides.contains("reference-overrides.conf") => MergeStrategy.concat
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

Universal / javaOptions ++= Seq(
  "-Dpidfile.path=/dev/null"
)
