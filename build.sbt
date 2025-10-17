ThisBuild / organization := "io.parqueteer"
ThisBuild / version := "0.1.0-SNAPSHOT"
name := "parqueteer"
ThisBuild / scalaVersion := "3.7.3"

ThisBuild / javaOptions ++= Seq("-source", "25", "-target", "25")
ThisBuild / scalacOptions ++= Seq(
  "-encoding",
  "utf8",
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Werror",
  "-Wunused:all"
)

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    assembly / mainClass := Some("io.parqueteer.cli.CliApp"),
    assembly / assemblyJarName := "parqueteer.jar",
    Compile / mainClass := Some("io.parqueteer.cli.CliApp"),

    // Assembly optimizations - enable caching for faster incremental builds
    assembly / assemblyOption := (assembly / assemblyOption).value.withCacheOutput(true),

    // Exclude test-only dependencies from assembly
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filter { jar =>
        val name = jar.data.getName.toLowerCase
        // Exclude test jars, documentation, source jars
        name.contains("scalatest") ||
        name.contains("scalamock") ||
        name.contains("scalacheck") ||
        name.contains("-sources") ||
        name.contains("-javadoc")
      }
    },

    // Add JVM options to suppress warnings in packaged distribution
    bashScriptExtraDefines += """addJava "--add-opens=java.base/java.lang=ALL-UNNAMED"""",
    bashScriptExtraDefines += """addJava "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"""",
    bashScriptExtraDefines += """addJava "-Xmx1G"""",

    // For Windows batch scripts
    batScriptExtraDefines += """set "_JAVA_OPTS=%_JAVA_OPTS% --add-opens=java.base/java.lang=ALL-UNNAMED"""",
    batScriptExtraDefines += """set "_JAVA_OPTS=%_JAVA_OPTS% --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"""",
    batScriptExtraDefines += """set "_JAVA_OPTS=%_JAVA_OPTS% -Xmx1G"""",
    libraryDependencies ++= {
      val parquet4sVersion =
        "2.18.0" // Latest that definitely exists for Scala 3
      val circeVersion = "0.14.10"
      val circeYamlVersion = "0.15.3"
      val scoptVersion = "4.1.0"
      val betterFilesVersion = "3.9.2"
      val logbackVersion = "1.5.15"
      val slf4jVersion = "2.0.16"
      val scalatestVersion = "3.2.18"
      val scalamockVersion = "6.0.0" // Latest for Scala 3
      val scalatestScalacheckVersion = "3.2.18.0"
      val parserCombinatorsVersion = "2.4.0"
      val awsSdkVersion = "2.29.35"
      val googleCloudStorageVersion = "2.45.0"
      val azureStorageVersion = "12.28.1"
      val azureIdentityVersion = "1.15.1"
      val hadoopVersion = "3.4.2"

      Seq(
        "com.github.mjakubowski84" %% "parquet4s-core" % parquet4sVersion,
        "com.github.scopt" %% "scopt" % scoptVersion,

        // Parser combinators for filter expressions
        "org.scala-lang.modules" %% "scala-parser-combinators" % parserCombinatorsVersion,

        // JSON processing
        "io.circe" %% "circe-core" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "io.circe" %% "circe-parser" % circeVersion,
        "io.circe" %% "circe-yaml" % circeYamlVersion,

        // File I/O
        "com.github.pathikrit" %% "better-files" % betterFilesVersion,

        // Logging
        "org.slf4j" % "slf4j-api" % slf4jVersion,
        "ch.qos.logback" % "logback-classic" % logbackVersion,

        // Cloud Storage - AWS
        "software.amazon.awssdk" % "s3" % awsSdkVersion,
        "software.amazon.awssdk" % "sts" % awsSdkVersion,
        "org.apache.hadoop" % "hadoop-aws" % hadoopVersion,

        // Cloud Storage - Google Cloud
        "com.google.cloud" % "google-cloud-storage" % googleCloudStorageVersion,
        "com.google.cloud.bigdataoss" % "gcs-connector" % "hadoop3-2.2.19",

        // Cloud Storage - Azure
        "com.azure" % "azure-storage-blob" % azureStorageVersion,
        "com.azure" % "azure-identity" % azureIdentityVersion,
        "org.apache.hadoop" % "hadoop-azure" % hadoopVersion,

        // Hadoop core for cloud filesystem support
        "org.apache.hadoop" % "hadoop-client-api" % hadoopVersion,
        "org.apache.hadoop" % "hadoop-client-runtime" % hadoopVersion,

        // Testing
        "org.scalatest" %% "scalatest" % scalatestVersion % Test,
        "org.scalamock" %% "scalamock" % scalamockVersion % Test,
        "org.scalatestplus" %% "scalacheck-1-17" % scalatestScalacheckVersion % Test
      )
    },
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
      case PathList("META-INF", "versions", xs @ _*) => MergeStrategy.first
      case PathList("META-INF", "LICENSE" | "LICENSE.txt" | "NOTICE" | "NOTICE.txt", xs @ _*) => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("module-info.class") => MergeStrategy.discard
      case PathList("reference.conf")    => MergeStrategy.concat
      case _                             => MergeStrategy.first
    }
  )
