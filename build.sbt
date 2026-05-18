ThisBuild / organization := "io.github.yusukensanta"
// Version managed by sbt-ci-release from git tags
name := "parqueteer"
ThisBuild / scalaVersion := "3.7.4"

// sbt-ci-release configuration
ThisBuild / versionScheme := Some("early-semver")

// Maven Central publishing configuration (Central Portal via sbt 1.11+)
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }

// Exclude publishing settings from lintUnused check
// These are used by sbt-ci-release plugin at runtime
Global / excludeLintKeys += publishMavenStyle
Global / excludeLintKeys += pomIncludeRepository

// POM metadata (required by Maven Central)
ThisBuild / licenses := Seq(
  "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
)
ThisBuild / developers := List(
  Developer(
    id = "yusukensanta",
    name = "Yusuke Nakayama",
    email = "yusukensanta@gmail.com", // TODO: Update with your actual email
    url = url("https://github.com/yusukensanta")
  )
)
ThisBuild / homepage := Some(url("https://github.com/yusukensanta/parqueteer"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/yusukensanta/parqueteer"),
    "scm:git@github.com:yusukensanta/parqueteer.git"
  )
)

ThisBuild / scalacOptions ++= Seq(
  "-encoding",
  "utf8",
  "-feature",
  "-language:implicitConversions",
  "-unchecked",
  "-Werror",
  "-Wunused:all"
)

coverageMinimumStmtTotal := 80
coverageFailOnMinimum := false

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "io.github.yusukensanta.parqueteer",
    assembly / mainClass := Some(
      "io.github.yusukensanta.parqueteer.cli.CliApp"
    ),
    assembly / assemblyJarName := "parqueteer.jar",
    Compile / mainClass := Some(
      "io.github.yusukensanta.parqueteer.cli.CliApp"
    ),

    // Assembly optimizations - enable caching for faster incremental builds
    assembly / assemblyOption := (assembly / assemblyOption).value
      .withCacheOutput(true),

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

    // Universal packaging configuration for distribution
    Universal / packageName := s"${name.value}-${version.value}",
    Universal / topLevelDirectory := Some(s"${name.value}-${version.value}"),

    // Include README and other docs in distribution
    Universal / mappings ++= Seq(
      (ThisBuild / baseDirectory).value / "README.md" -> "README.md",
      (ThisBuild / baseDirectory).value / "LICENSE" -> "LICENSE"
    ).filter(_._1.exists),

    // Exclude assembly JAR from Universal package (Universal creates its own structure)
    Universal / mappings := {
      val original = (Universal / mappings).value
      // Keep only the staged files from JavaAppPackaging, not assembly output
      original.filterNot { case (file, path) =>
        path.contains("parqueteer.jar") && !path.startsWith("lib/")
      }
    },
    libraryDependencies ++= {
      val parquet4sVersion = "2.22.0" // parquet-hadoop 1.15.2 — fixes CVE-2025-30065 + CVE-2025-46762
      val circeVersion = "0.14.13"
      val circeYamlVersion = "0.16.0"
      val scoptVersion = "4.1.0"
      val betterFilesVersion = "3.9.2"
      val slf4jVersion = "2.0.17"
      val scalatestVersion = "3.2.19"
      val scalamockVersion = "7.3.2"
      val scalatestScalacheckVersion = "3.2.18.0"
      val parserCombinatorsVersion = "2.4.0"
      val awsSdkVersion = "2.34.0"
      val googleCloudStorageVersion = "2.68.0"
      val azureStorageVersion = "12.30.0"
      val azureIdentityVersion = "1.16.2"
      val hadoopVersion = "3.5.0"
      val gcsConnectorVersion = "hadoop3-2.2.28"

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

        // Logging - using simple logger for smaller distribution
        "org.slf4j" % "slf4j-simple" % slf4jVersion,

        // ================================================================
        // Cloud Storage - AWS S3 (Optimized to exclude 641 MB bundle)
        // ================================================================

        // Hadoop AWS connector for s3a:// URIs - EXCLUDE the bundle!
        "org.apache.hadoop" % "hadoop-aws" % hadoopVersion
          exclude ("software.amazon.awssdk", "bundle"),

        // Add only the AWS SDK v2 modules we need (instead of 641 MB bundle)
        "software.amazon.awssdk" % "s3" % awsSdkVersion,
        "software.amazon.awssdk" % "sts" % awsSdkVersion,
        "software.amazon.awssdk" % "sso" % awsSdkVersion,
        "software.amazon.awssdk" % "ssooidc" % awsSdkVersion,
        "software.amazon.awssdk" % "apache-client" % awsSdkVersion,

        // ================================================================
        // Cloud Storage - Google Cloud (Optimized)
        // ================================================================

        // GCS connector for Hadoop (gs:// URIs)
        "com.google.cloud.bigdataoss" % "gcs-connector" % gcsConnectorVersion
          exclude ("com.google.guava", "guava") // Avoid version conflicts
          exclude ("org.apache.hadoop", "hadoop-common"), // Already included

        // Native GCS client (for direct API usage)
        "com.google.cloud" % "google-cloud-storage" % googleCloudStorageVersion,

        // ================================================================
        // Cloud Storage - Azure (Optimized)
        // ================================================================

        // Hadoop Azure connector for wasb:// URIs
        "org.apache.hadoop" % "hadoop-azure" % hadoopVersion
          exclude ("org.apache.hadoop", "hadoop-common"), // Already included

        // Native Azure client (for direct API usage)
        "com.azure" % "azure-storage-blob" % azureStorageVersion,
        "com.azure" % "azure-identity" % azureIdentityVersion,

        // ================================================================
        // Hadoop Core (Minimal for cloud filesystem support)
        // ================================================================

        "org.apache.hadoop" % "hadoop-client-api" % hadoopVersion,
        "org.apache.hadoop" % "hadoop-client-runtime" % hadoopVersion,

        // Testing
        "org.scalatest" %% "scalatest" % scalatestVersion % Test,
        "org.scalamock" %% "scalamock" % scalamockVersion % Test,
        "org.scalatestplus" %% "scalacheck-1-17" % scalatestScalacheckVersion % Test
      )
    },
    // Force Netty to latest 4.1.x to patch transitive CVEs from AWS SDK
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-common"        % "4.1.121.Final",
      "io.netty" % "netty-buffer"        % "4.1.121.Final",
      "io.netty" % "netty-handler"       % "4.1.121.Final",
      "io.netty" % "netty-transport"     % "4.1.121.Final",
      "io.netty" % "netty-codec"         % "4.1.121.Final",
      "io.netty" % "netty-codec-http"    % "4.1.121.Final",
      "io.netty" % "netty-codec-http2"   % "4.1.121.Final"
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
      case PathList("META-INF", "versions", xs @ _*) => MergeStrategy.first
      case PathList(
            "META-INF",
            "LICENSE" | "LICENSE.txt" | "NOTICE" | "NOTICE.txt",
            xs @ _*
          ) =>
        MergeStrategy.discard
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("module-info.class") => MergeStrategy.discard
      case PathList("reference.conf")    => MergeStrategy.concat
      case _                             => MergeStrategy.first
    }
  )

// Version is automatically managed by sbt-ci-release from git tags
// No manual release process needed - just push a tag to trigger CI publishing
