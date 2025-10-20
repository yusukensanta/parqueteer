addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.13.0")
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0")

// Maven Central publishing via CI
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.9.3")
