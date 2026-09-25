
name := "dnpm-cdn-etl"
ThisBuild / organization := "de.dnpm"
ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version      := sys.env.getOrElse("VERSION","1.0.0")

val ownerRepo  = sys.env.getOrElse("REPOSITORY","dnpm-dip/cdn-etl-apps").split("/")
ThisBuild / githubOwner      := ownerRepo(0)
ThisBuild / githubRepository := ownerRepo(1)


ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.first
  case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
  case "reference.conf"                          => MergeStrategy.concat
  case _                                         => MergeStrategy.last
}


//-----------------------------------------------------------------------------
// PROJECTS
//-----------------------------------------------------------------------------

lazy val global = project
  .in(file("."))
  .settings(
    settings,
    publish / skip := true
  )
  .aggregate(
    util,
    diagnosis_codes
  )

lazy val util = project
  .settings(
    name := "cdn-etl-util",
    settings,
    libraryDependencies ++= Seq(
//      dependencies.scalatest,
      dependencies.service_base,
      dependencies.play_ahc,
      dependencies.play_ahc_js,
    ),
    assembly / assemblyJarName := "dnpm-cdn-etl-util.jar",
  )


lazy val diagnosis_codes = project
  .settings(
    name := "cdn-diagnosis-etl",
    settings,
    libraryDependencies ++= Seq(
      dependencies.scalatest,
      dependencies.logback,
      dependencies.mtb_model,
      dependencies.rd_model,
      dependencies.bfarm_model
    ),
    assembly / assemblyJarName := "dnpm-cdn-diagnosis-etl.jar",
    assembly / mainClass       := Some("de.dnpm.cdn.etl.diagnoses.Processor")
  )
  .dependsOn(util)


//-----------------------------------------------------------------------------
// DEPENDENCIES
//-----------------------------------------------------------------------------

lazy val dependencies =
  new {
    val scalatest    = "org.scalatest"     %% "scalatest"               % "3.2.20" % Test
    val logback      = "ch.qos.logback"    %  "logback-classic"         % "1.5.18"
    val play_ahc     = "org.playframework" %% "play-ahc-ws-standalone"  % "3.0.7"
    val play_ahc_js  = "org.playframework" %% "play-ws-standalone-json" % "3.0.7"
    val service_base = "de.dnpm.dip"       %% "service-base"            % "1.5.1"
    val mtb_model    = "de.dnpm.dip"       %% "mtb-dto-model"           % "1.2.3"
    val rd_model     = "de.dnpm.dip"       %% "rd-dto-model"            % "1.2.1"
    val bfarm_model  = "de.dnpm"           %% "dnpm-bfarm-model-base"   % "1.0.5"
  }


//-----------------------------------------------------------------------------
// SETTINGS
//-----------------------------------------------------------------------------

lazy val settings = commonSettings

// Compiler options from: https://alexn.org/blog/2020/05/26/scala-fatal-warnings/
lazy val compilerOptions = Seq(
  // Feature options
  "-encoding", "utf-8",
  "-explaintypes",
  "-feature",
  "-language:existentials",
  "-language:experimental.macros",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:postfixOps",
  "-Ymacro-annotations",

  // Warnings as errors!
  "-Xfatal-warnings",

  // Linting options
  "-unchecked",
  "-Xcheckinit",
  "-Xlint:adapted-args",
  "-Xlint:constant",
  "-Xlint:delayedinit-select",
  "-Xlint:deprecation",
  "-Xlint:doc-detached",
  "-Xlint:inaccessible",
  "-Xlint:infer-any",
  "-Xlint:missing-interpolator",
  "-Xlint:nullary-unit",
  "-Xlint:option-implicit",
  "-Xlint:package-object-classes",
  "-Xlint:poly-implicit-overload",
  "-Xlint:private-shadow",
  "-Xlint:stars-align",
  "-Xlint:type-parameter-shadow",
  "-Wdead-code",
  "-Wextra-implicit",
  "-Wnumeric-widen",
  "-Wunused:imports",
  "-Wunused:locals",
  "-Wunused:patvars",
  "-Wunused:privates",
  "-Wunused:implicits",
  "-Wvalue-discard",
)


lazy val commonSettings = Seq(
  scalacOptions ++= compilerOptions,
  resolvers ++= Seq(
    "Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository",
    Resolver.githubPackages("dnpm-dip"),
    Resolver.githubPackages("KohlbacherLab"),
    Resolver.sonatypeCentralSnapshots
  )

)

