ThisBuild / organization := "io.github.yusukensanta"
// Version managed by sbt-ci-release from git tags
name := "parqueteer"
ThisBuild / scalaVersion := "3.7.4"

// sbt-ci-release configuration
ThisBuild / versionScheme := Some("early-semver")


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

// Fork test JVM so AWS SDK service registry has a flat classpath.
// Without forking, sbt's layered classloader hides transitive deps that
// S3Client initialization scans via SPI, causing NoClassDefFoundError.
Test / fork := true

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion),
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
    bashScriptExtraDefines += """addJava "-Dfile.encoding=UTF-8"""",
    bashScriptExtraDefines += """addJava "-Dstdout.encoding=UTF-8"""",

    // For Windows batch scripts
    batScriptExtraDefines += """set "_JAVA_OPTS=%_JAVA_OPTS% --add-opens=java.base/java.lang=ALL-UNNAMED"""",
    batScriptExtraDefines += """set "_JAVA_OPTS=%_JAVA_OPTS% --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"""",
    batScriptExtraDefines += """set "_JAVA_OPTS=%_JAVA_OPTS% -Xmx1G"""",
    batScriptExtraDefines += """set "_JAVA_OPTS=%_JAVA_OPTS% -Dfile.encoding=UTF-8"""",
    batScriptExtraDefines += """set "_JAVA_OPTS=%_JAVA_OPTS% -Dstdout.encoding=UTF-8"""",

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
      val parquet4sVersion = "2.23.0"
      val circeVersion = "0.14.14"
      val circeYamlV12Version = "0.16.1"
      val scoptVersion = "4.1.0"
      val betterFilesVersion = "3.9.2"
      val slf4jVersion = "2.0.18"
      val scalatestVersion = "3.2.20"
      val scalamockVersion = "7.5.5"
      val scalatestScalacheckVersion = "3.2.18.0"
      val awsSdkVersion = "2.44.12"
      val googleCloudStorageVersion = "2.68.0"
      val azureStorageVersion = "12.30.0"
      val azureIdentityVersion = "1.16.2"
      val hadoopVersion = "3.5.0"
      val gcsConnectorVersion = "hadoop3-2.2.28"

      Seq(
        "com.github.mjakubowski84" %% "parquet4s-core" % parquet4sVersion,
        "com.github.scopt" %% "scopt" % scoptVersion,

        // JSON processing
        "io.circe" %% "circe-core" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "io.circe" %% "circe-parser" % circeVersion,
        "io.circe" %% "circe-yaml-v12" % circeYamlV12Version,

        // File I/O
        "com.github.pathikrit" %% "better-files" % betterFilesVersion,

        // Logging - using simple logger for smaller distribution
        "org.slf4j" % "slf4j-simple" % slf4jVersion,
        // Bridge JUL→SLF4J so GCS connector logs are filtered by simplelogger.properties
        "org.slf4j" % "jul-to-slf4j" % slf4jVersion,

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
        "software.amazon.awssdk" % "s3-transfer-manager" % awsSdkVersion,
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

        // commons-lang3 required by hadoop-aws 3.5.0 S3AUtils at S3AFileSystem.initialize()
        "org.apache.commons" % "commons-lang3" % "3.18.0",

        // Testing
        "org.scalatest" %% "scalatest" % scalatestVersion % Test,
        "org.scalamock" %% "scalamock" % scalamockVersion % Test,
        "org.scalatestplus" %% "scalacheck-1-17" % scalatestScalacheckVersion % Test
      )
    },
    // Force Netty to 4.1.126.Final across all modules (current latest stable).
    // CVE-2024-47535 fixed in 4.1.115.Final; CVE-2025-55163 fixed in 4.1.124.Final.
    // Prior override covered only 7 of 19 modules; 12 modules resolved at mixed 4.1.112-4.1.133.
    dependencyOverrides ++= {
      val nettyVersion = "4.1.126.Final"
      Seq(
        "io.netty" % "netty-common"                       % nettyVersion,
        "io.netty" % "netty-buffer"                       % nettyVersion,
        "io.netty" % "netty-handler"                      % nettyVersion,
        "io.netty" % "netty-transport"                    % nettyVersion,
        "io.netty" % "netty-codec"                        % nettyVersion,
        "io.netty" % "netty-codec-http"                   % nettyVersion,
        "io.netty" % "netty-codec-http2"                  % nettyVersion,
        "io.netty" % "netty-resolver"                     % nettyVersion,
        "io.netty" % "netty-resolver-dns"                 % nettyVersion,
        "io.netty" % "netty-codec-dns"                    % nettyVersion,
        "io.netty" % "netty-codec-socks"                  % nettyVersion,
        "io.netty" % "netty-handler-proxy"                % nettyVersion,
        "io.netty" % "netty-transport-classes-epoll"      % nettyVersion,
        "io.netty" % "netty-transport-classes-kqueue"     % nettyVersion,
        "io.netty" % "netty-transport-native-epoll"       % nettyVersion,
        "io.netty" % "netty-transport-native-kqueue"      % nettyVersion,
        "io.netty" % "netty-transport-native-unix-common" % nettyVersion,
        "io.netty" % "netty-resolver-dns-classes-macos"   % nettyVersion,
        "io.netty" % "netty-resolver-dns-native-macos"    % nettyVersion
      )
    },
    // Transitive CVE patches
    dependencyOverrides ++= Seq(
      "org.xerial.snappy" % "snappy-java"     % "1.1.10.8", // CVE-2024-36124 — Snappy frame OOM (parquet reads untrusted files)
      "com.nimbusds"      % "nimbus-jose-jwt" % "10.4",     // ES256K mishandling in 10.0.x (azure-identity path)
      "net.minidev"       % "json-smart"      % "2.5.2"     // CVE-2025-27817 — nested JSON stack overflow (nimbus transitive); 2.5.3 not published, latest 2.5.x used
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

// Version is automatically managed by sbt-dynver from git tags
// No manual release process needed - just push a tag to trigger CI publishing
