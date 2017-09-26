
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.{ContentTypes, Multipart, HttpResponse, HttpRequest}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.event.{LoggingAdapter, Logging}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl._

import JsonHelp._

import akka.http.scaladsl.model._
import akka.http.scaladsl.client.RequestBuilding._
import com.typesafe.config.Config
import org.apache.nifi.web.api.dto.flow.FlowDTO
import org.apache.nifi.web.api.dto.status.ProcessorStatusDTO
import org.apache.nifi.web.api.dto.{ProcessorDTO, ProcessorConfigDTO}
import org.apache.nifi.web.api.entity.{ProcessorEntity, ProcessGroupFlowEntity, ProcessGroupEntity}
import spray.json.{JsString, JsValue}
import scala.concurrent.{Await, Future, ExecutionContextExecutor}
import java.io.File
import akka.http.scaladsl.marshalling.Marshal

import NifiApiModel._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

import scala.collection.JavaConverters._

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import spray.json.DefaultJsonProtocol._
import Utils._
import HttpUtils._
import spray.json._

import scala.util.{Failure, Try}
import scala.xml.NodeSeq

//TODO we've arbitrarily specified component positions in some places.
//With importTemplateIntoProcessGroupJson we had to, otherwise we'd get this error:400 Bad Request. Response body was The origin position (x, y) must be specified.
//So what do we do about component positions?
class NifiInteractions(val config: Config, theLogger: LoggingAdapter)(implicit  system: ActorSystem, executor: ExecutionContextExecutor, materializer: Materializer) extends Protocol {

  val DoNotRunProcessorsWithThisName = "Dummy"

  implicit val logger: LoggingAdapter = theLogger

  val apiPath = config.getString("services.nifi-api.path")
  val templateDir = config.getString("template.directory")
  val replace: (String, Map[String, String]) => String = Misc.replaceText("\\{\\{", "}}") _


  //According to documentation I read somewhere, if we need to increase the size of file uploads,
  //we can set this option in application.conf:
  // akka.http.server.parsing.max-content-length = 512m
  object TemplateUploadViaMultipart {
    def createEntity(file: File): Future[RequestEntity] = {
      require(file.exists())
      //Looks like we're using this HttpEntity apply method:
      //apply(contentType: ContentType, contentLength: Long, data: Source[ByteString, Any])
      def httpEntity = HttpEntity(MediaTypes.`multipart/form-data`, file.length(), FileIO.fromPath(file.toPath, chunkSize = 10000)) // the chunk size here is currently critical for performance
      val formData =
        Multipart.FormData(
          Source.single(
            Multipart.FormData.BodyPart(
              "template",
              httpEntity,
              Map("filename" -> file.getName))))
      Marshal(formData).to[RequestEntity]
    }

    def createEntityFromText(text: String, fileName: String): Future[RequestEntity] = {
      val formData =
        Multipart.FormData(
          Multipart.FormData.BodyPart.Strict(
            "template",
            HttpEntity(ContentTypes.`text/xml(UTF-8)`, text),
            Map("filename" -> fileName))
        )
      Marshal(formData).to[RequestEntity]
    }

    def createRequest(target: Uri, file: File): Future[HttpRequest] =
      for {
        e ← createEntityFromText( Misc.readText(file.getPath), file.getName)   //createEntity(file)
      } yield HttpRequest(HttpMethods.POST, uri = target, entity = e)

    def createRequest(target: Uri, text: String, filename: String): Future[HttpRequest] =
      for {
        e ← createEntityFromText( text, filename)
      } yield HttpRequest(HttpMethods.POST, uri = target, entity = e)

  }


  def nifiUri(path: Uri.Path): Uri = {
    Uri(scheme = "http", authority = Uri.Authority(Uri.Host(config.getString("services.nifi-api.host")), port = config.getInt("services.nifi-api.port")), path = path)
  }

  //We provide a position here. Not sure we have to do that.
  def createProcessGroupJson(name: String, clientId: String): String = {
    s"""{"revision":{"clientId":"$clientId","version":0},"component":{"name":"$name","position":{"x":181,"y":140.5}}}"""
  }

  //If we don't provide originX and originY, we see:
  //400 Bad Request. Response body was The origin position (x, y) must be specified.
  def importTemplateIntoProcessGroupJson(templateId: String): String = s"""{"templateId":"${templateId}","originX":385,"originY":170}"""

  /**
   *
   * example:
   * curl 'http://localhost:8080/nifi-api/process-groups/${processGroupId}/template-instance' \
   * -H 'Content-Type: application/json' \
   * --data '{"templateId":"${templateId}","originX":385,"originY":170}'
   */
  def importTemplateIntoProcessGroup(processGroupId: String, templateId: String): Future[NifiApiModel.NifiFlow] = {
    HttpRequest(HttpMethods.POST, uri = nifiUri(Uri.Path(s"$apiPath/process-groups/${processGroupId}/template-instance")),
      entity = HttpEntity.apply(ContentTypes.`application/json`, importTemplateIntoProcessGroupJson(templateId))
    ).withResp(respEntity => Unmarshal(respEntity).to[JsValue].flatMap { js =>
      deep(js, List("flow")).map(innerJs => Future.successful(innerJs.convertTo[NifiApiModel.NifiFlow])).recover {
        case ex => Future.failed(ex)
      }.get
    }

    )

  }

  def findRootProcessGroupId: Future[String] = {
    HttpRequest(HttpMethods.GET, uri = nifiUri(Uri.Path(s"$apiPath/flow/process-groups/root"))).withResp(Unmarshal(_).to[JsValue]).flatMap{jsValue =>

      (for {
        processGroupFlowJsValue: JsValue <- deep(jsValue, List("processGroupFlow"))
        theMap: Map[String, JsValue] <-  asMap(processGroupFlowJsValue)
        rootProcessGroupId: String <- theMap.get("id").map(_ match {
          case JsString(id) => scala.util.Success(id)
          case other => scala.util.Failure(new JsonReadError(s"expected processGroupFlow/id to be JsString. Was ${other.getClass} with value $other"))
        }
        ).getOrElse{
          scala.util.Failure(new JsonReadError(s"expected key processGroupFlow/id is missing"))
        }
      } yield rootProcessGroupId).toFuture
    }

  }

  def findClientId: Future[String] = {
    HttpRequest(HttpMethods.GET, uri = nifiUri(Uri.Path(s"$apiPath/flow/client-id"))).withResp(Unmarshal(_).to[String])
  }

  //Nifi gives us an xml response here whether we ask for it or not.
  def uploadTemplate(parentProcessGroupId: String, text: String, filename: String): Future[String] = {
    for {
      templateUploadReq <- TemplateUploadViaMultipart.createRequest(
        nifiUri(Uri.Path(s"$apiPath/process-groups/${parentProcessGroupId}/templates/upload")),
        text, filename)
      xml: NodeSeq <- templateUploadReq.withResp(Unmarshal(_).to[NodeSeq])
    } yield (xml \ "template" \ "id").headOption.map{_.text.trim}.get
  }

  //As of this writing this is the only place we use NIFI's JAXB representation.
  def createProcessGroup(parentProcessGroupId: String, processGroupName: String, clientId: String): Future[ProcessGroupEntity] = {
    HttpRequest(HttpMethods.POST, uri = nifiUri(Uri.Path(s"$apiPath/process-groups/${parentProcessGroupId}/process-groups")),
      entity = HttpEntity.apply(ContentTypes.`application/json`, createProcessGroupJson(processGroupName, clientId))
    ).withResp(respEntity =>   Unmarshal(respEntity).to[String].map(JaxBConverters.JsonConverters.fromJsonString[ProcessGroupEntity]) )

  }

  def updateStateOfHttpContextMap(updateInfo: UpdateInfo): Future[Unit] = {
    HttpRequest(HttpMethods.PUT, uri = nifiUri(Uri.Path(s"$apiPath/controller-services/${updateInfo.component.id}")),
      entity = HttpEntity.apply(ContentTypes.`application/json`, updateInfo.toJson.compactPrint)
    ).withResp{unitReturnAndDiscardBytes }

  }

  def updateStateOfOneProcessor(updateInfo: UpdateInfo): Future[Unit] = {
    HttpRequest(HttpMethods.PUT, uri = nifiUri(Uri.Path(s"$apiPath/processors/${updateInfo.component.id}")),
      entity = HttpEntity.apply(ContentTypes.`application/json`, updateInfo.toJson.compactPrint)
    ).withResp(unitReturnAndDiscardBytes )

  }



  def runMany[K](ids: Vector[K], f: K => Future[Unit], successMsg: String, failureMsg: String): Future[Unit] = {
    runManyGeneric[K, Unit](ids, f).flatMap(_ match {
      case Right(vec) =>
        logger.info(s"$successMsg ${vec.map(_._1).mkString(",")}")
        Future.successful(())
      case Left(vec) => Future.failed( new RuntimeException(
        failureMsg  + vec.map(_._1).mkString(",")  ,
        vec.head._2)   )
    })


  }

  //Could we go back to parsing required valued directly from JsValues rather than from case classes?
  //If not, get rid of this.
  def componentInfoFromMap(map: Map[String, JsValue]): Try[ComponentInfo] = {

    val tuple: (Option[JsValue], Option[JsValue], Option[JsValue]) = (map.get("id"), map.get("name"), map.get("state"))

    tuple match {
      case (Some(jsValId), Some(jsValName), Some(jsValState)) =>
        (jsValId, jsValName, jsValState) match {
          case (JsString(id), JsString(name), JsString(state)) => scala.util.Success(ComponentInfo(id, name, state))
          case _ => Failure(JsonReadError(s"Could not parse ComponentInfo from ${map}"))
        }

      case _ => Failure(JsonReadError(s"Could not parse ComponentInfo from ${map}"))
    }


  }

  //Could we go back to parsing required valued directly from JsValues rather than from case classes?
  //If not, get rid of this.
  def findIdsOfHttpContextMapsAndNonRunningComponents(bigJsValue: JsValue): Try[(Set[String], Vector[ComponentInfo])] = {
    val theComponents: Try[Vector[JsValue]] = {
      for {
        jsArrayOfProcessors: JsValue <- deep(bigJsValue, List("flow", "processors"))
        vecOfJsProcessors: Vector[JsValue] <- jsArrayToVector(jsArrayOfProcessors)
        processorComponents <- Misc.sequence(vecOfJsProcessors.map(processorJsValue => deep(processorJsValue, List("component"))))
      } yield processorComponents
    }

    val tryOfHttpContextMapIds: Try[Set[String]] = theComponents.flatMap { comps: Vector[JsValue] =>
      Misc.sequence(comps.map(comp =>
        deep(comp, List("config", "properties")).flatMap(asMap).map(_.get("HTTP Context Map"))
      )
      )
    }.map { v: Vector[Option[JsValue]] =>
      v.collect { case Some(jsValue) => jsValue }
    }.flatMap{vec: Vector[JsValue] =>
      Misc.sequence(vec.map(asString)).map(_.to[Set])
    }


    val tryOfComponentsThatNeedToBeEnabled: Try[Vector[ComponentInfo]] = theComponents.flatMap{comps: Vector[JsValue] =>
      Misc.sequence(comps.map(comp => asMap(comp).flatMap(componentInfoFromMap)   ))
    }.map(_.filter(ci => ci.name != DoNotRunProcessorsWithThisName && ci.state != "RUNNING"))


    Misc.tryMap2(tryOfHttpContextMapIds, tryOfComponentsThatNeedToBeEnabled)( (_,_) )

  }

  def findHttpContextMapIds(flow: NifiFlow): Set[String] = {
    flow.processors.map(
      _.component.config.properties.get("HTTP Context Map")
    ).collect{ case Some(JsString(id)) =>
      id
    }.toSet

  }

  def replaceText(inputData: InputData): Future[String] = {

    val stringPath = s"$templateDir/${inputData.templateFileName}.xml"

    (for {
      path <-  if(new File(stringPath).exists) scala.util.Success(stringPath) else Failure(new ABadRequest(s"No corresponding template for ${inputData.templateFileName}"))
      replaced <- Try( replace(Misc.readText(path), inputData.templateReplacementValues)  )
      _ <- if( replaced.indexOf("}}") >= 0 )
        Failure(new ABadRequest(s"templateReplacementValues were not complete. After applying them, some replace tokens still existed in the ${inputData.templateFileName} template file."))
      else scala.util.Success(() )
    } yield replaced).toFuture


  }

  //TODO: I'd like to stream data from our template file, use Framing to cut the file into lines, do text replace on the streaming lines,
  //and stream the replaced lines as we upload.  But don't know how to do that now.
  //This article doesn't quite do that, but at least it shows the use of framing: https://stackoverflow.com/questions/40224457/reading-a-csv-files-using-akka-streams)
  def createAndStartProcessGroup(inputData: InputData): Future[Unit] = {

    for {

      replacedText <- replaceText(inputData)

      nifiRootProcessorGroupId <- findRootProcessGroupId

      templateId <- uploadTemplate(nifiRootProcessorGroupId, replacedText, inputData.templateFileName)

      clientId <-  findClientId

      processGroupEntity: ProcessGroupEntity <- createProcessGroup(nifiRootProcessorGroupId, inputData.processorGroupName, clientId)

      nifiFlow <- importTemplateIntoProcessGroup(processGroupEntity.getId, templateId)

      (httpContextMapIds, processorsWeNeedToGetRunning) = (
        findHttpContextMapIds(nifiFlow) ,
        nifiFlow.processors.filter(p => p.component.name != "Dummy" && p.component.state != "RUNNING")
        )


      _ <- runMany[UpdateInfo](
      ids = httpContextMapIds.map(contextMapId => UpdateInfo(revision = Revision(0, Some(clientId)), component = UpdateComponentInfo(contextMapId, "ENABLED" ) )   ).toVector,
      f = updateStateOfHttpContextMap,
      successMsg = "set all Http Context Maps to enabled state. Ids are:",
      failureMsg = "Failed to set these Http Context Maps to enabled state:")

      //Don't know if we need to supply the clientId we got in findClientId, or just use the one that's already in the revision. Maybe it doesn't matter
      processorUpdates = processorsWeNeedToGetRunning.map(p => UpdateInfo(p.revision.copy(clientId = Some(clientId)), UpdateComponentInfo(p.id, "RUNNING")) )

      _ <- runMany[UpdateInfo](
      ids = processorUpdates,
      f = updateStateOfOneProcessor,
      successMsg = "set all desired runnable processors to running state. Ids of these processors are:",
      failureMsg = "Failed to set these processors to runnable state:")

    } yield ()
  }




}
