import java.io.{File, PrintWriter}
import java.net.URL
import org.apache.commons.io.FileUtils
import sbt.ExclusionRule

import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}
import BuildUtils._
import xerial.sbt.Sonatype._

val condaEnvName = "synapseml"
val sparkVersion = "3.1.2"
name := "synapseml"
ThisBuild / organization := "com.microsoft.azure"
ThisBuild / scalaVersion := "2.12.10"

val scalaMajorVersion = 2.12

val excludes = Seq(
  ExclusionRule("org.apache.spark", s"spark-tags_$scalaMajorVersion"),
  ExclusionRule("org.scalatest")
)

val coreDependencies = Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion % "compile",
  "org.apache.spark" %% "spark-mllib" % sparkVersion % "compile",
  "org.apache.spark" %% "spark-avro" % sparkVersion % "provided",
  "org.apache.spark" %% "spark-tags" % sparkVersion % "test",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test")
val extraDependencies = Seq(
  "org.scalactic" %% "scalactic" % "3.0.5",
  "io.spray" %% "spray-json" % "1.3.2",
  "com.jcraft" % "jsch" % "0.1.54",
  "org.apache.httpcomponents" % "httpclient" % "4.5.6",
  "org.apache.httpcomponents" % "httpmime" % "4.5.6",
  "com.linkedin.isolation-forest" %% "isolation-forest_3.0.0" % "1.0.1",
).map(d => d excludeAll (excludes: _*))
val dependencies = coreDependencies ++ extraDependencies

def txt(e: Elem, label: String): String = "\"" + e.child.filter(_.label == label).flatMap(_.text).mkString + "\""

val omittedDeps = Set(s"spark-core_$scalaMajorVersion", s"spark-mllib_$scalaMajorVersion", "org.scala-lang")
// skip dependency elements with a scope

def pomPostFunc(node: XmlNode): scala.xml.Node = {
  new RuleTransformer(new RewriteRule {
    override def transform(node: XmlNode): XmlNodeSeq = node match {
      case e: Elem if e.label == "extraDependencyAttributes" =>
        Comment("Removed Dependency Attributes")
      case e: Elem if e.label == "dependency"
        && e.child.exists(child => child.label == "scope") =>
        Comment(
          s""" scoped dependency ${txt(e, "groupId")} % ${txt(e, "artifactId")}
             |% ${txt(e, "version")} % ${txt(e, "scope")} has been omitted """.stripMargin)
      case e: Elem if e.label == "dependency"
        && e.child.exists(child => omittedDeps(child.text)) =>
        Comment(
          s""" excluded dependency ${txt(e, "groupId")} % ${txt(e, "artifactId")}
             |% ${txt(e, "version")} has been omitted """.stripMargin)
      case _ => node
    }
  }).transform(node).head
}

pomPostProcess := pomPostFunc

val speechResolver = "Speech" at "https://mmlspark.blob.core.windows.net/maven/"

val getDatasetsTask = TaskKey[Unit]("getDatasets", "download datasets used for testing")
val datasetName = "datasets-2021-12-10.tgz"
val datasetUrl = new URL(s"https://mmlspark.blob.core.windows.net/installers/$datasetName")
val datasetDir = settingKey[File]("The directory that holds the dataset")
ThisBuild / datasetDir := {
  join(artifactPath.in(packageBin).in(Compile).value.getParentFile,
    "datasets", datasetName.split(".".toCharArray.head).head)
}

getDatasetsTask := {
  val d = datasetDir.value.getParentFile
  val f = new File(d, datasetName)
  if (!d.exists()) d.mkdirs()
  if (!f.exists()) {
    FileUtils.copyURLToFile(datasetUrl, f)
    UnzipUtils.unzip(f, d)
  }
}

val genBuildInfo = TaskKey[Unit]("genBuildInfo", "generate a build info file")
genBuildInfo := {
  val docInfo =
    s"""
       |
       |### Documentation Pages:
       |[Scala Documentation](https://mmlspark.blob.core.windows.net/docs/${version.value}/scala/index.html)
       |[Python Documentation](https://mmlspark.blob.core.windows.net/docs/${version.value}/pyspark/index.html)
       |
    """.stripMargin
  val buildInfo = (root / blobArtifactInfo).value + docInfo
  val infoFile = join("target", "Build.md")
  if (infoFile.exists()) FileUtils.forceDelete(infoFile)
  FileUtils.writeStringToFile(infoFile, buildInfo, "utf-8")
}

val rootGenDir = SettingKey[File]("rootGenDir")
rootGenDir := {
  val targetDir = artifactPath.in(packageBin).in(Compile).in(root).value.getParentFile
  join(targetDir, "generated")
}

def runTaskForAllInCompile(task: TaskKey[Unit]): Def.Initialize[Task[Seq[Unit]]] = {
  task.all(ScopeFilter(
    inProjects(core, deepLearning, cognitive, vw, lightgbm, opencv),
    inConfigurations(Compile))
  )
}

val generatePythonDoc = TaskKey[Unit]("generatePythonDoc", "Generate sphinx docs for python")
generatePythonDoc := {
  runTaskForAllInCompile(installPipPackage).value
  runTaskForAllInCompile(mergePyCode).value
  val dir = join(rootGenDir.value, "src", "python", "synapse")
  join(dir, "__init__.py").createNewFile()
  join(dir, "ml", "__init__.py").createNewFile()
  runCmd(activateCondaEnv ++ Seq("sphinx-apidoc", "-f", "-o", "doc", "."), dir)
  runCmd(activateCondaEnv ++ Seq("sphinx-build", "-b", "html", "doc", "../../../doc/pyspark"), dir)
}

val packageSynapseML = TaskKey[Unit]("packageSynapseML", "package all projects into SynapseML")
packageSynapseML := {
  def writeSetupFileToTarget(dir: File): Unit = {
    if (!dir.exists()) {
      dir.mkdir()
    }
    val content =
      s"""
         |# Copyright (C) Microsoft Corporation. All rights reserved.
         |# Licensed under the MIT License. See LICENSE in project root for information.
         |
         |import os
         |from setuptools import setup, find_namespace_packages
         |import codecs
         |import os.path
         |
         |setup(
         |    name="synapseml",
         |    version="${pythonizedVersion(version.value)}",
         |    description="Synpase Machine Learning",
         |    long_description="SynapseML contains Microsoft's open source "
         |                     + "contributions to the Apache Spark ecosystem",
         |    license="MIT",
         |    packages=find_namespace_packages(include=['synapse.ml.*']),
         |    url="https://github.com/Microsoft/SynapseML",
         |    author="Microsoft",
         |    author_email="mmlspark-support@microsoft.com",
         |    classifiers=[
         |        "Development Status :: 4 - Beta",
         |        "Intended Audience :: Developers",
         |        "Intended Audience :: Science/Research",
         |        "Topic :: Software Development :: Libraries",
         |        "License :: OSI Approved :: MIT License",
         |        "Programming Language :: Python :: 2",
         |        "Programming Language :: Python :: 3",
         |    ],
         |    zip_safe=True,
         |    package_data={"synapseml": ["../LICENSE.txt", "../README.txt"]},
         |)
         |
         |""".stripMargin
    IO.write(join(dir, "setup.py"), content)
  }

  Def.sequential(
    runTaskForAllInCompile(packagePython),
    runTaskForAllInCompile(mergePyCode)
  ).value
  val targetDir = rootGenDir.value
  val dir = join(targetDir, "src", "python")
  val packageDir = join(targetDir, "package", "python").absolutePath
  writeSetupFileToTarget(dir)
  packagePythonWheelCmd(packageDir, dir)
}

val publishPypi = TaskKey[Unit]("publishPypi", "publish synapseml python wheel to pypi")
publishPypi := {
  packageSynapseML.value
  val fn = s"${name.value}-${pythonizedVersion(version.value)}-py2.py3-none-any.whl"
  runCmd(
    activateCondaEnv ++
      Seq("twine", "upload", "--skip-existing",
        join(rootGenDir.value, "package", "python", fn).toString,
        "--username", "__token__", "--password", Secrets.pypiApiToken, "--verbose")
  )
}

val publishDocs = TaskKey[Unit]("publishDocs", "publish docs for scala and python")
publishDocs := {
  generatePythonDoc.value
  (root / Compile / unidoc).value
  val html =
    """
      |<html><body><pre style="font-size: 150%;">
      |<a href="pyspark/index.html">pyspark/</u>
      |<a href="scala/index.html">scala/</u>
      |</pre></body></html>
    """.stripMargin
  val targetDir = artifactPath.in(packageBin).in(Compile).in(root).value.getParentFile
  val codegenDir = join(targetDir, "generated")
  val unifiedDocDir = join(codegenDir, "doc")
  val scalaDir = join(unifiedDocDir.toString, "scala")
  if (scalaDir.exists()) FileUtils.forceDelete(scalaDir)
  FileUtils.copyDirectory(join(targetDir, "unidoc"), scalaDir)
  FileUtils.writeStringToFile(join(unifiedDocDir.toString, "index.html"), html, "utf-8")
  uploadToBlob(unifiedDocDir.toString, version.value, "docs")
}

val release = TaskKey[Unit]("release", "publish the library to synapseml blob")
release := Def.taskDyn {
  val v = isSnapshot.value
  if (!v) {
    Def.task {
      sonatypeBundleRelease.value
    }
  } else {
    Def.task {
      "Not a release"
    }
  }
}

val publishBadges = TaskKey[Unit]("publishBadges", "publish badges to synapseml blob")
publishBadges := {
  def enc(s: String): String = {
    s.replaceAllLiterally("_", "__").replaceAllLiterally(" ", "_").replaceAllLiterally("-", "--")
  }

  def uploadBadge(left: String, right: String, color: String, filename: String): Unit = {
    val badgeDir = join(baseDirectory.value.toString, "target", "badges")
    if (!badgeDir.exists()) badgeDir.mkdirs()
    runCmd(Seq("curl",
      "-o", join(badgeDir.toString, filename).toString,
      s"https://img.shields.io/badge/${enc(left)}-${enc(right)}-${enc(color)}"))
    singleUploadToBlob(
      join(badgeDir.toString, filename).toString,
      s"badges/$filename", "icons", extraArgs = Seq("--content-cache-control", "no-cache"))
  }

  uploadBadge("master version", version.value, "blue", "master_version3.svg")
}

val settings = Seq(
  (scalastyleConfig in Test) := (ThisBuild / baseDirectory).value / "scalastyle-test-config.xml",
  logBuffered in Test := false,
  parallelExecution in Test := false,
  test in assembly := {},
  assemblyMergeStrategy in assembly := {
    case PathList("META-INF", xs@_*) => MergeStrategy.discard
    case x => MergeStrategy.first
  },
  assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false),
  autoAPIMappings := true,
  pomPostProcess := pomPostFunc,
  sbtPlugin := false
)
ThisBuild / publishMavenStyle := true

lazy val core = (project in file("core"))
  .enablePlugins(BuildInfoPlugin && SbtPlugin)
  .settings(settings ++ Seq(
    libraryDependencies ++= dependencies,
    buildInfoKeys ++= Seq[BuildInfoKey](
      datasetDir,
      version,
      scalaVersion,
      sbtVersion,
      baseDirectory
    ),
    name := "synapseml-core",
    buildInfoPackage := "com.microsoft.azure.synapse.ml.build"
  ): _*)

lazy val deepLearning = (project in file("deep-learning"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core % "test->test;compile->compile", opencv % "test->test;compile->compile")
  .settings(settings ++ Seq(
    libraryDependencies ++= Seq(
      "com.microsoft.cntk" % "cntk" % "2.4",
      "com.microsoft.onnxruntime" % "onnxruntime_gpu" % "1.8.1"
    ),
    name := "synapseml-deep-learning"
  ): _*)

lazy val lightgbm = (project in file("lightgbm"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core % "test->test;compile->compile")
  .settings(settings ++ Seq(
    libraryDependencies += ("com.microsoft.ml.lightgbm" % "lightgbmlib" % "3.2.110"),
    name := "synapseml-lightgbm"
  ): _*)

lazy val vw = (project in file("vw"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core % "test->test;compile->compile")
  .settings(settings ++ Seq(
    libraryDependencies += ("com.github.vowpalwabbit" % "vw-jni" % "8.9.1"),
    name := "synapseml-vw"
  ): _*)

lazy val cognitive = (project in file("cognitive"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core % "test->test;compile->compile")
  .settings(settings ++ Seq(
    libraryDependencies ++= Seq(
      "com.microsoft.cognitiveservices.speech" % "client-sdk" % "1.14.0",
      "com.azure" % "azure-storage-blob" % "12.8.0", // can't upgrade higher due to conflict with jackson-databind
),
    resolvers += speechResolver,
    name := "synapseml-cognitive"
  ): _*)

lazy val opencv = (project in file("opencv"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core % "test->test;compile->compile")
  .settings(settings ++ Seq(
    libraryDependencies += ("org.openpnp" % "opencv" % "3.2.0-1"),
    name := "synapseml-opencv"
  ): _*)

lazy val root = (project in file("."))
  .aggregate(core, deepLearning, cognitive, vw, lightgbm, opencv)
  .dependsOn(
    core % "test->test;compile->compile",
    deepLearning % "test->test;compile->compile",
    cognitive % "test->test;compile->compile",
    vw % "test->test;compile->compile",
    lightgbm % "test->test;compile->compile",
    opencv % "test->test;compile->compile")
  .enablePlugins(ScalaUnidocPlugin && SbtPlugin)
  .disablePlugins(CodegenPlugin)
  .settings(settings ++ Seq(
    name := "synapseml",
  ))

val setupTask = TaskKey[Unit]("setup", "set up library for intellij")
setupTask := {
  compile.all(ScopeFilter(
    inProjects(root, core, deepLearning, cognitive, vw, lightgbm, opencv),
    inConfigurations(Compile, Test))
  ).value
  getDatasetsTask.value
}

val convertNotebooks = TaskKey[Unit]("convertNotebooks",
  "convert notebooks to markdown for website display")
convertNotebooks := {
  runCmd(
    Seq("python", s"${join(baseDirectory.value, "website/notebookconvert.py")}")
  )
}

val testWebsiteDocs = TaskKey[Unit]("testWebsiteDocs",
  "test code blocks inside markdowns under folder website/docs/documentation")
testWebsiteDocs := {
  runCmd(
    Seq("python", s"${join(baseDirectory.value, "website/doctest.py")}")
  )
}

ThisBuild / sonatypeProjectHosting := Some(
  GitHubHosting("Azure", "SynapseML", "mmlspark-support@microsot.com"))
ThisBuild / homepage := Some(url("https://github.com/Microsoft/SynapseML"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/Azure/SynapseML"),
    "scm:git@github.com:Azure/SynapseML.git"
  )
)
ThisBuild / developers := List(
  Developer("mhamilton723", "Mark Hamilton",
    "mmlspark-support@microsoft.com", url("https://github.com/mhamilton723")),
  Developer("imatiach-msft", "Ilya Matiach",
    "mmlspark-support@microsoft.com", url("https://github.com/imatiach-msft")),
  Developer("drdarshan", "Sudarshan Raghunathan",
    "mmlspark-support@microsoft.com", url("https://github.com/drdarshan"))
)

ThisBuild / licenses += ("MIT", url("https://github.com/Microsoft/SynapseML/blob/master/LICENSE"))

ThisBuild / credentials += Credentials("Sonatype Nexus Repository Manager",
  "oss.sonatype.org",
  Secrets.nexusUsername,
  Secrets.nexusPassword)

pgpPassphrase := Some(Secrets.pgpPassword.toCharArray)
pgpSecretRing := {
  val temp = File.createTempFile("secret", ".asc")
  new PrintWriter(temp) {
    write(Secrets.pgpPrivate)
    close()
  }
  temp
}
pgpPublicRing := {
  val temp = File.createTempFile("public", ".asc")
  new PrintWriter(temp) {
    write(Secrets.pgpPublic)
    close()
  }
  temp
}
ThisBuild / publishTo := sonatypePublishToBundle.value

dynverSonatypeSnapshots in ThisBuild := true
dynverSeparator in ThisBuild := "-"
