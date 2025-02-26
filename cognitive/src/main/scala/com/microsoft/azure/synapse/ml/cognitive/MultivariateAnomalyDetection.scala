// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.azure.synapse.ml.cognitive

import com.azure.storage.blob.sas.{BlobSasPermission, BlobServiceSasSignatureValues}
import com.azure.storage.blob.{BlobContainerClient, BlobServiceClientBuilder}
import com.azure.storage.common.StorageSharedKeyCredential
import com.microsoft.azure.synapse.ml.build.BuildInfo
import com.microsoft.azure.synapse.ml.codegen.Wrappable
import com.microsoft.azure.synapse.ml.cognitive.MADJsonProtocol._
import com.microsoft.azure.synapse.ml.cognitive.RESTHelpers.Client
import com.microsoft.azure.synapse.ml.core.contracts.{HasInputCols, HasOutputCol}
import com.microsoft.azure.synapse.ml.io.http.HandlingUtils.{convertAndClose, sendWithRetries}
import com.microsoft.azure.synapse.ml.io.http._
import com.microsoft.azure.synapse.ml.logging.BasicLogging
import org.apache.commons.io.IOUtils
import org.apache.http.client.methods.{HttpEntityEnclosingRequestBase, HttpGet, HttpPost, HttpRequestBase}
import org.apache.http.entity.{AbstractHttpEntity, ContentType, StringEntity}
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.spark.ml._
import org.apache.spark.ml.param._
import org.apache.spark.ml.util._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Dataset, Encoders, Row}
import spray.json._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, OutputStream, PrintWriter}
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeoutException
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.concurrent.blocking
import scala.language.existentials

trait MADHttpRequest extends HasURL with HasSubscriptionKey with HasAsyncReply {
  protected def prepareUrl: String

  protected def prepareMethod(): HttpRequestBase = new HttpPost()

  protected def prepareEntity(source: String): Option[AbstractHttpEntity]

  protected val subscriptionKeyHeaderName = "Ocp-Apim-Subscription-Key"

  protected def contentType: String = "application/json"

  protected def prepareRequest(entity: AbstractHttpEntity): Option[HttpRequestBase] = {
    val req = prepareMethod()
    req.setURI(new URI(prepareUrl))
    req.setHeader(subscriptionKeyHeaderName, getSubscriptionKey)
    req.setHeader("Content-Type", contentType)

    req match {
      case er: HttpEntityEnclosingRequestBase =>
        er.setEntity(entity)
      case _ =>
    }
    Some(req)
  }

  protected def queryForResult(key: Option[String],
                               client: CloseableHttpClient,
                               location: URI): Option[HTTPResponseData] = {
    val get = new HttpGet()
    get.setURI(location)
    key.foreach(get.setHeader("Ocp-Apim-Subscription-Key", _))
    get.setHeader("User-Agent", s"synapseml/${BuildInfo.version}${HeaderValues.PlatformInfo}")
    val resp = convertAndClose(sendWithRetries(client, get, getBackoffs))
    get.releaseConnection()
    val fields = IOUtils.toString(resp.entity.get.content, "UTF-8").parseJson.asJsObject.fields
    val status = if (fields.keySet.contains("modelInfo")) {
      fields("modelInfo").asInstanceOf[JsObject].fields
        .get("status").map(_.convertTo[String]).get.toLowerCase()
    } else if (fields.keySet.contains("summary")) {
      fields("summary").asInstanceOf[JsObject]
        .fields.get("status").map(_.convertTo[String]).get.toLowerCase()
    } else {
      "None"
    }
    status match {
      case "ready" | "failed" => Some(resp)
      case "created" | "running" => None
      case s => throw new RuntimeException(s"Received unknown status code: $s")
    }
  }

  protected def handlingFunc(client: CloseableHttpClient,
                             request: HTTPRequestData): HTTPResponseData = {
    val response = HandlingUtils.advanced(getBackoffs: _*)(client, request)
    if (response.statusLine.statusCode == 201) {
      val location = new URI(response.headers.filter(_.name == "Location").head.value)
      val maxTries = getMaxPollingRetries
      val key = request.headers.find(_.name == "Ocp-Apim-Subscription-Key").map(_.value)
      val it = (0 to maxTries).toIterator.flatMap { _ =>
        queryForResult(key, client, location).orElse({
          blocking {
            Thread.sleep(getPollingDelay.toLong)
          }
          None
        })
      }
      if (it.hasNext) {
        it.next()
      } else {
        throw new TimeoutException(
          s"Querying for results did not complete within $maxTries tries")
      }
    } else {
      response
    }
  }
}

trait MADBase extends HasOutputCol
  with MADHttpRequest with HasSetLocation with HasInputCols
  with HTTPParams with ComplexParamsWritable with Wrappable
  with HasSubscriptionKey with HasErrorCol with BasicLogging {

  val startTime = new Param[String](this, "startTime", "A required field, start time" +
    " of data to be used for detection/generating multivariate anomaly detection model, should be date-time.")

  def setStartTime(v: String): this.type = set(startTime, v)

  def getStartTime: String = $(startTime)

  val endTime = new Param[String](this, "endTime", "A required field, end time of data" +
    " to be used for detection/generating multivariate anomaly detection model, should be date-time.")

  def setEndTime(v: String): this.type = set(endTime, v)

  def getEndTime: String = $(endTime)

  val timestampCol = new Param[String](this, "timestampCol", "Timestamp column name")

  def setTimestampCol(v: String): this.type = set(timestampCol, v)

  def getTimestampCol: String = $(timestampCol)

  setDefault(timestampCol -> "timestamp")

  val intermediateSaveDir = new Param[String](this, "intermediateSaveDir", "Directory name " +
    "of which you want to save the intermediate data produced while training.")

  def setIntermediateSaveDir(v: String): this.type = set(intermediateSaveDir, v)

  def getIntermediateSaveDir: String = $(intermediateSaveDir)

  val connectionString = new Param[String](this, "connectionString", "Connection String " +
    "for your storage account used for uploading files.")

  def setConnectionString(v: String): this.type = set(connectionString, v)

  def getConnectionString: Option[String] = get(connectionString)

  val storageName = new Param[String](this, "storageName", "Storage Name " +
    "for your storage account used for uploading files.")

  def setStorageName(v: String): this.type = set(storageName, v)

  def getStorageName: Option[String] = get(storageName)

  val storageKey = new Param[String](this, "storageKey", "Storage Key " +
    "for your storage account used for uploading files.")

  def setStorageKey(v: String): this.type = set(storageKey, v)

  def getStorageKey: Option[String] = get(storageKey)

  val endpoint = new Param[String](this, "endpoint", "End Point " +
    "for your storage account used for uploading files.")

  def setEndpoint(v: String): this.type = set(endpoint, v)

  def getEndpoint: Option[String] = get(endpoint)

  val sasToken = new Param[String](this, "sasToken", "SAS Token " +
    "for your storage account used for uploading files.")

  def setSASToken(v: String): this.type = set(sasToken, v)

  def getSASToken: Option[String] = get(sasToken)

  val containerName = new Param[String](this, "containerName", "Container that will be " +
    "used to upload files to.")

  def setContainerName(v: String): this.type = set(containerName, v)

  def getContainerName: String = $(containerName)

  setDefault(
    outputCol -> (this.uid + "_output"),
    errorCol -> (this.uid + "_error"))

  protected def getBlobContainerClient: BlobContainerClient = {
    if (this.get(connectionString).nonEmpty) {
      getBlobContainerClient(getConnectionString.get, getContainerName)
    } else if (this.get(storageName).nonEmpty && this.get(storageKey).nonEmpty &&
      this.get(endpoint).nonEmpty && this.get(sasToken).nonEmpty) {
      getBlobContainerClient(getStorageName.get, getStorageKey.get, getEndpoint.get,
        getSASToken.get, getContainerName)
    } else {
      throw new Exception("You need to set either {connectionString, containerName} or " +
        "{storageName, storageKey, endpoint, sasToken, containerName} in order to access the blob container")
    }
  }

  protected def getBlobContainerClient(storageConnectionString: String,
                                       containerName: String): BlobContainerClient = {
    val blobContainerClient = new BlobServiceClientBuilder()
      .connectionString(storageConnectionString)
      .credential(StorageSharedKeyCredential.fromConnectionString(storageConnectionString))
      .buildClient()
      .getBlobContainerClient(containerName.toLowerCase())
    if (!blobContainerClient.exists()) {
      blobContainerClient.create()
    }
    blobContainerClient
  }

  protected def getBlobContainerClient(storageName: String, storageKey: String, endpoint: String,
                                       sasToken: String, containerName: String): BlobContainerClient = {
    val blobContainerClient = new BlobServiceClientBuilder()
      .endpoint(endpoint)
      .sasToken(sasToken)
      .credential(new StorageSharedKeyCredential(storageName, storageKey))
      .buildClient()
      .getBlobContainerClient(containerName.toLowerCase())
    if (!blobContainerClient.exists()) {
      blobContainerClient.create()
    }
    blobContainerClient
  }

  protected def blobName: String = s"$getIntermediateSaveDir/$uid.zip"

  protected def upload(blobContainerClient: BlobContainerClient, df: DataFrame): String = {
    val timestampColumn = df.schema
      .find(p => p.name == getTimestampCol)
      .get
    val timestampColIdx = df.schema.indexOf(timestampColumn)
    val rows = df.collect
    val zipTargetStream = new ByteArrayOutputStream()
    val zipOut = new ZipOutputStream(zipTargetStream)

    // loop over all features
    for (feature <- df.schema.filter(p => p != timestampColumn).zipWithIndex) {
      val featureIdx = df.schema.indexOf(feature._1)
      // create zip entry. must be named series_{idx}
      zipOut.putNextEntry(new ZipEntry(s"series_${feature._2}.csv"))
      // write CSV
      storeFeatureInCsv(rows, timestampColIdx, featureIdx, zipOut)
      zipOut.closeEntry()
    }
    zipOut.close()

    // upload zip file
    val zipInBytes = zipTargetStream.toByteArray
    val blobClient = blobContainerClient.getBlobClient(blobName)
    blobClient.upload(new ByteArrayInputStream(zipInBytes), zipInBytes.length, true)

    // generate SAS
    val sas = blobClient.generateSas(new BlobServiceSasSignatureValues(
      OffsetDateTime.now().plusHours(24),
      new BlobSasPermission().setReadPermission(true)
    ))

    s"${blobClient.getBlobUrl}?${sas}"
  }

  protected def storeFeatureInCsv(rows: Array[Row], timestampColIdx: Int, featureIdx: Int, out: OutputStream): Unit = {
    // create CSV file per feature
    val pw = new PrintWriter(out)

    // CSV header
    pw.println("timestamp,value")

    for (row <- rows) {
      // <timestamp>,<value>
      // make sure it's ISO8601. e.g. 2021-01-01T00:00:00Z
      val timestamp = DateTimeFormatter.ISO_INSTANT.parse(row.getString(timestampColIdx))

      pw.print(DateTimeFormatter.ISO_INSTANT.format(timestamp))
      pw.write(',')

      // TODO: do we have to worry about locale?
      // pw.format(Locale.US, "%f", row.get(featureIdx))
      pw.println(row.get(featureIdx))
    }
    pw.flush()
  }

  def cleanUpIntermediateData(): Unit = {
    val blobContainerClient = getBlobContainerClient
    blobContainerClient.getBlobClient(blobName).delete()
  }


}

object FitMultivariateAnomaly extends ComplexParamsReadable[FitMultivariateAnomaly] with Serializable

class FitMultivariateAnomaly(override val uid: String) extends Estimator[DetectMultivariateAnomaly]
  with MADBase {
  logClass()

  def this() = this(Identifiable.randomUID("FitMultivariateAnomaly"))

  def urlPath: String = "anomalydetector/v1.1-preview/multivariate/models"

  val slidingWindow = new IntParam(this, "slidingWindow", "An optional field, indicates" +
    " how many history points will be used to determine the anomaly score of one subsequent point.")

  def setSlidingWindow(v: Int): this.type = {
    if ((v >= 28) && (v <= 2880)) {
      set(slidingWindow, v)
    } else {
      throw new IllegalArgumentException("slidingWindow must be between 28 and 2880 (both inclusive).")
    }
  }

  def getSlidingWindow: Option[Int] = get(slidingWindow)

  val alignMode = new Param[String](this, "alignMode", "An optional field, indicates how " +
    "we align different variables into the same time-range which is required by the model.{Inner, Outer}")

  def setAlignMode(v: String): this.type = {
    if (Set("inner", "outer").contains(v.toLowerCase)) {
      set(alignMode, v.toLowerCase.capitalize)
    } else {
      throw new IllegalArgumentException("alignMode must be either `inner` or `outer`.")
    }
  }

  def getAlignMode: Option[String] = get(alignMode)

  val fillNAMethod = new Param[String](this, "fillNAMethod", "An optional field, indicates how missed " +
    "values will be filled with. Can not be set to NotFill, when alignMode is Outer.{Previous, Subsequent," +
    " Linear, Zero, Fixed}")

  def setFillNAMethod(v: String): this.type = {
    if (Set("previous", "subsequent", "linear", "zero", "fixed").contains(v.toLowerCase)) {
      set(fillNAMethod, v.toLowerCase.capitalize)
    } else {
      throw new IllegalArgumentException("fillNAMethod must be one of {Previous, Subsequent, Linear, Zero, Fixed}.")
    }
  }

  def getFillNAMethod: Option[String] = get(fillNAMethod)

  val paddingValue = new IntParam(this, "paddingValue", "optional field, is only useful" +
    " if FillNAMethod is set to Fixed.")

  def setPaddingValue(v: Int): this.type = set(paddingValue, v)

  def getPaddingValue: Option[Int] = get(paddingValue)

  val displayName = new Param[String](this, "displayName", "optional field," +
    " name of the model")

  def setDisplayName(v: String): this.type = set(displayName, v)

  def getDisplayName: Option[String] = get(displayName)

  val diagnosticsInfo = new Param[DiagnosticsInfo](this, "diagnosticsInfo",
    "diagnosticsInfo for training a multivariate anomaly detection model")

  def setDiagnosticsInfo(v: DiagnosticsInfo): this.type = set(diagnosticsInfo, v)

  def getDiagnosticsInfo: Option[DiagnosticsInfo] = get(diagnosticsInfo)

  protected def prepareEntity(source: String): Option[AbstractHttpEntity] = {
    Some(new StringEntity(MAERequest(source, getStartTime, getEndTime, getSlidingWindow,
      Option(AlignPolicy(getAlignMode, getFillNAMethod, getPaddingValue)), getDisplayName)
      .toJson.compactPrint, ContentType.APPLICATION_JSON))
  }

  protected def prepareUrl: String = getUrl

  override def fit(dataset: Dataset[_]): DetectMultivariateAnomaly = {
    logFit({

      val blobContainerClient = getBlobContainerClient
      val df = dataset.toDF().select((Array(getTimestampCol) ++ getInputCols).map(col): _*)
      val sasUrl = upload(blobContainerClient, df)

      val httpRequestBase = prepareRequest(prepareEntity(sasUrl).get)
      val request = new HTTPRequestData(httpRequestBase.get)
      val response = handlingFunc(Client, request)

      val responseDict = IOUtils.toString(response.entity.get.content, "UTF-8")
        .parseJson.asJsObject.fields

      this.setDiagnosticsInfo(responseDict("modelInfo").asJsObject.fields
        .get("diagnosticsInfo").map(_.convertTo[DiagnosticsInfo]).get)

      val simpleDetectMultivariateAnomaly = new DetectMultivariateAnomaly()
        .setSubscriptionKey(getSubscriptionKey)
        .setLocation(getUrl.split("/".toCharArray)(2).split(".".toCharArray).head)
        .setModelId(responseDict.get("modelId").map(_.convertTo[String]).get)
        .setContainerName(getContainerName)
        .setIntermediateSaveDir(getIntermediateSaveDir)

      if (this.get(connectionString).nonEmpty) {
        simpleDetectMultivariateAnomaly
          .setConnectionString(getConnectionString.get)
      } else {
        simpleDetectMultivariateAnomaly
          .setStorageName(getStorageName.get)
          .setStorageKey(getStorageKey.get)
          .setEndpoint(getEndpoint.get)
          .setSASToken(getSASToken.get)
      }
    })
  }

  override def copy(extra: ParamMap): Estimator[DetectMultivariateAnomaly] = defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = {
    schema.add(getErrorCol, DMAError.schema)
      .add(getOutputCol, DMAResponse.schema)
      .add("isAnomaly", BooleanType)
      .add("isAnomaly", BooleanType)
  }

}

object DetectMultivariateAnomaly extends ComplexParamsReadable[DetectMultivariateAnomaly] with Serializable

class DetectMultivariateAnomaly(override val uid: String) extends Model[DetectMultivariateAnomaly]
  with MADBase {
  logClass()

  def this() = this(Identifiable.randomUID("DetectMultivariateAnomaly"))

  def urlPath: String = "anomalydetector/v1.1-preview/multivariate/models/"

  val modelId = new Param[String](this, "modelId", "Format - uuid. Model identifier.")

  def setModelId(v: String): this.type = set(modelId, v)

  def getModelId: String = $(modelId)

  protected def prepareEntity(source: String): Option[AbstractHttpEntity] = {
    Some(new StringEntity(
      DMARequest(source, getStartTime, getEndTime)
        .toJson.compactPrint))
  }

  protected def prepareUrl: String = getUrl + s"${getModelId}/detect"

  override def transform(dataset: Dataset[_]): DataFrame = {
    logTransform[DataFrame] {

      val blobContainerClient = getBlobContainerClient
      val df = dataset.select((Array(getTimestampCol) ++ getInputCols).map(col): _*)
        .sort(col(getTimestampCol).asc).toDF()
      val sasUrl = upload(blobContainerClient, df)

      val httpRequestBase = prepareRequest(prepareEntity(sasUrl).get)
      val request = new HTTPRequestData(httpRequestBase.get)
      val response = handlingFunc(Client, request)

      val responseJson = IOUtils.toString(response.entity.get.content, "UTF-8")
        .parseJson.asJsObject.fields("results").toString()

      val outputDF = df.sparkSession.read
        .json(df.sparkSession.createDataset(Seq(responseJson))(Encoders.STRING))
        .toDF()
        .sort(col("timestamp").asc)
        .withColumnRenamed("timestamp", "resultTimestamp")

      val outputDF2 = if (outputDF.columns.contains("value")) {
        outputDF.withColumn("isAnomaly", col("value.isAnomaly"))
          .withColumnRenamed("value", getOutputCol)
      } else {
        outputDF.withColumn(getOutputCol, lit(None))
          .withColumn("isAnomaly", lit(None))
      }

      val finalDF = if (outputDF2.columns.contains("errors")) {
        outputDF2.withColumnRenamed("errors", getErrorCol)
      } else {
        outputDF2.withColumn(getErrorCol, lit(None))
      }

      df.join(finalDF, df(getTimestampCol) === finalDF("resultTimestamp"), "left")
        .drop("resultTimestamp")
    }
  }

  override def copy(extra: ParamMap): DetectMultivariateAnomaly = defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = {
    schema.add(getErrorCol, DMAError.schema)
      .add(getOutputCol, DMAResponse.schema)
      .add("isAnomaly", BooleanType)
  }

}
