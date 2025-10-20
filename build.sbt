import ReleaseTransformations._

ThisBuild / organization := "com.github.yusukensanta"
// Version managed by sbt-ci-release from git tags
name := "parqueteer"
ThisBuild / scalaVersion := "3.7.3"

// sbt-ci-release configuration
ThisBuild / versionScheme := Some("early-semver")

// Maven Central publishing configuration
ThisBuild / sonatypeCredentialHost := "central.sonatype.com"
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }

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
    assembly / mainClass := Some(
      "com.github.yusukensanta.parqueteer.cli.CliApp"
    ),
    assembly / assemblyJarName := "parqueteer.jar",
    Compile / mainClass := Some(
      "com.github.yusukensanta.parqueteer.cli.CliApp"
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

// Release configuration
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies, // Check no SNAPSHOT dependencies
  inquireVersions, // Ask for release and next version
  runClean, // Clean before build
  runTest, // Run tests
  setReleaseVersion, // Set version to release version
  commitReleaseVersion, // Commit the release version
  tagRelease, // Tag the release (v{version})
  setNextVersion, // Set version to next SNAPSHOT
  commitNextVersion, // Commit the next SNAPSHOT version
  pushChanges // Push commits and tags to remote
)

// Don't publish to Maven/Sonatype (we use GitHub Releases instead)
releasePublishArtifactsAction := {}

// Use minor version bump by default (1.0.0 -> 1.1.0)
releaseVersionBump := sbtrelease.Version.Bump.Minor

// Custom tag name format (adds 'v' prefix: v1.0.0)
releaseTagName := s"v${
    if (releaseUseGlobalVersion.value) (ThisBuild / version).value
    else version.value
  }"

// Require clean working directory before release
releaseIgnoreUntrackedFiles := false
