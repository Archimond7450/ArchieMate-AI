import scala.sys.process._

// Global settings
ThisBuild / organization := "com.archimond7450"
ThisBuild / scalaVersion := "3.6.4"
ThisBuild / version := "0.0.0.1"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-language:implicitConversions"
)
ThisBuild / testFrameworks += new TestFramework("org.scalatest.tools.Framework")

// Common settings for JVM projects
lazy val commonJvmSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest-core" % "3.2.19" % Test,
    "org.scalatest" %% "scalatest-flatspec" % "3.2.19" % Test,
    "org.scalatest" %% "scalatest-shouldmatchers" % "3.2.19" % Test
  )
)

// ==================== Backend ====================
lazy val backend = project
  .in(file("backend"))
  .enablePlugins(JavaAppPackaging)
  .settings(commonJvmSettings)
  .settings(
    name := "archiemate-backend",
    libraryDependencies ++= Seq(
      // Pekko - all at same version
      "org.apache.pekko" %% "pekko-actor" % "1.1.5",
      "org.apache.pekko" %% "pekko-slf4j" % "1.1.5",
      "org.apache.pekko" %% "pekko-actor-typed" % "1.1.5",
      "org.apache.pekko" %% "pekko-stream" % "1.1.5",
      "org.apache.pekko" %% "pekko-http" % "1.1.0",
      "org.apache.pekko" %% "pekko-serialization-jackson" % "1.1.5",
      "org.apache.pekko" %% "pekko-persistence" % "1.1.5",
      "org.apache.pekko" %% "pekko-persistence-typed" % "1.1.5",
      "org.apache.pekko" %% "pekko-persistence-jdbc" % "1.2.0",
      "org.apache.pekko" %% "pekko-testkit" % "1.1.5" % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % "1.1.0" % Test,
      // Circe
      "io.circe" %% "circe-core" % "0.14.14",
      "io.circe" %% "circe-generic" % "0.14.14",
      "io.circe" %% "circe-parser" % "0.14.14",
      // Config
      "com.typesafe" % "config" % "1.4.3",
      // Logging
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      // Testcontainers for PostgreSQL in tests (add when needed)
      // "org.testcontainers" % "postgresql" % "1.20.6" % Test
    )
  )

// ==================== Frontend (Scala.js) ====================
lazy val frontend = project
  .in(file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "archiemate-frontend",
    // Use NoModule for standalone JS (production Docker build)
    // ESModule is used for Vite dev server (frontend/dev mode)
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.NoModule) },
    // Set the main class for the JS output
    Compile / mainClass := Some("com.archimond7450.archiemate.App"),
    // Version info for footer
    Compile / sourceGenerators += Def.task {
      val versionInfo = s"""|package com.archimond7450.archiemate
                            |
                            |object VersionInfo {
                            |  val version: String = "${version.value}"
                            |  val builtAt: String = "${java.time.Instant.now().toString}"
                            |}
                            |""".stripMargin
      val file = (Compile / sourceManaged).value / "VersionInfo.scala"
      IO.write(file, versionInfo)
      Seq(file)
    }.taskValue,
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "2.8.0",
      "com.raquo" %%% "laminar" % "17.2.1",
      // Testing
      "org.scala-js" %%% "scalajs-dom" % "2.8.0" % Test,
      "org.scalatest" %%% "scalatest" % "3.2.19" % Test
    )
  )

// ==================== Shared (shared between backend and frontend) ====================
lazy val shared = project
  .in(file("shared"))
  .settings(commonJvmSettings)
  .settings(
    name := "archiemate-shared",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.14",
      "io.circe" %% "circe-generic" % "0.14.14",
      "io.circe" %% "circe-parser" % "0.14.14"
    )
  )

// ==================== Frontend Test (Vitest + Playwright) ====================
lazy val frontendTest = project
  .in(file("frontend-test"))
  .settings(
    name := "archiemate-frontend-test",
    // This project is primarily JS/Node-based for Vitest and Playwright
    // ScalaTest for Scala-side frontend testing is in frontend project
    publish / skip := true
  )

// ==================== Root ====================
lazy val root = project
  .in(file("."))
  .aggregate(backend, shared)
  .settings(
    name := "archiemate",
    publish / skip := true
  )

// Expose backend as the default project
lazy val archiemate = root
