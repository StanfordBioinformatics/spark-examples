import sbtassembly.AssemblyPlugin.autoImport._

name := "googlegenomics-spark-examples"

version := "1.0"

scalaVersion := "2.11.8"

scalacOptions += "-target:jvm-1.7"

val sparkVersion = "1.6.1"

val genomicsUtilsVersion = "v1-0.10"

scalaModuleInfo ~= (_.map(_.withOverrideScalaVersion(true)))

libraryDependencies ++= Seq(
  "com.google.cloud.genomics" % "google-genomics-utils" % genomicsUtilsVersion excludeAll(
      ExclusionRule(organization = "org.mortbay.jetty", name = "servlet-api"),
      ExclusionRule(organization = "com.google.guava", name = "guava-jdk5"),
      ExclusionRule(organization = "com.fasterxml.jackson.core", name = "jackson-core"),
      ExclusionRule(organization = "com.sun.mail", name="javax.mail")
  ),
  "org.apache.spark" %% "spark-core" % sparkVersion % "provided",
  "org.apache.spark" %% "spark-mllib" % sparkVersion % "provided",
  "org.rogach" %% "scallop" % "0.9.5",
  "org.scalatest" %% "scalatest" % "2.2.1" % "test",
  "io.netty" % "netty-tcnative" % "2.0.7.Final"
)

resolvers += Resolver.mavenLocal

assemblyMergeStrategy in assembly := {
    case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
}

assemblyShadeRules in assembly := Seq(
      ShadeRule.rename("io.netty.handler.**" -> "shadeio.io.netty.handler.@1").inAll,
      ShadeRule.rename("io.netty.channel.**" -> "shadeioi.io.netty.channel.@1").inAll,
      ShadeRule.rename("io.netty.util.**" -> "shadeio.io.netty.util.@1").inAll,
      ShadeRule.rename("io.netty.bootstrap.**" -> "shadeio.io.netty.bootstrap.@1").inAll,
      ShadeRule.rename("com.google.common.**" -> "shade.com.google.common.@1").inAll,
      ShadeRule.rename("com.google.protobuf.**" -> "shade.com.google.protobuf.@1").inAll,
    )
