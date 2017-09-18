
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.{ContentTypes, Multipart, HttpResponse, HttpRequest}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl._

import JsonHelp._

import akka.http.scaladsl.model._
import akka.http.scaladsl.client.RequestBuilding._
import com.typesafe.config.ConfigFactory
import org.apache.nifi.web.api.dto.flow.FlowDTO
import org.apache.nifi.web.api.dto.status.ProcessorStatusDTO
import org.apache.nifi.web.api.dto.{ProcessorDTO, ProcessorConfigDTO}
import org.apache.nifi.web.api.entity.{ProcessorEntity, ProcessGroupFlowEntity, ProcessGroupEntity}
import spray.json.JsValue
import scala.concurrent.{Await, Future, ExecutionContextExecutor}
import java.io.File
import akka.http.scaladsl.marshalling.Marshal

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

import scala.collection.JavaConverters._

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import spray.json.DefaultJsonProtocol._

import scala.util.Try
import scala.xml.NodeSeq

//TODO we've arbitrarily specified component positions in some places.
//With importTemplateIntoProcessGroupJson we had to, otherwise we'd get this error:400 Bad Request. Response body was The origin position (x, y) must be specified.
//So what do we do about component positions?
object Script extends App {

  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()

  lazy val config = ConfigFactory.load()

  //The value for my local nifi instance si 5cb229a2-015e-1000-af7e-47911f0b10d6
  val nifiRootProcessorGroupId = args(0)
  println(s"nifiRootProcessorGroupId is $nifiRootProcessorGroupId")
  val ourTemplateFile = new File(args(1))
  //TODO Get this from somewhere. It's the key-values for text replacement inside the chosen template
  val replaceTemplateValues =  """{"knownBrokers":"127.0.0.1:9093,127.0.0.1:9094","listeningPort":"9010","allowedPaths":"/data"}"""

  val apiPath = config.getString("services.nifi-api.path")
  val replace: (String, Map[String, String]) => String = Misc.replaceText("\\{\\{", "}}") _

  //TODO This would be re-usable if we passed in some values for the Multipar.FormData.BodyPart
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
      println(s"doing createEntityFromText")
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

  object NifiResponseHelp {

    def handleBadStatus[T](resp: HttpResponse): Future[T] = {
      Unmarshal(resp.entity).to[String].flatMap { bodyAsString =>
        Future.failed(new RuntimeException(s"failed with status ${resp.status}. Response body was $bodyAsString"))
      }
    }

    def getIt[T](httpRequest: HttpRequest, acceptableStatus: Set[Int] = Set(200, 201))(f: HttpEntity => Future[T]): Future[T] = {
      Http().singleRequest(httpRequest).flatMap { resp: HttpResponse =>
        resp.status.intValue match {
          case status if acceptableStatus.contains(status) => f(resp.entity)
          case other => handleBadStatus[T](resp)
        }

      }
    }
  }

  implicit class HttpRequestMonkeyPatch(val req: HttpRequest) extends AnyVal {
    import NifiResponseHelp._
    def withResp[T](f: HttpEntity => Future[T], acceptableStatus: Set[Int] = Set(200, 201)): Future[T] = {
      getIt(req, acceptableStatus)(f)
    }
  }

  def nifiUri(path: Uri.Path): Uri = {
    Uri(scheme = "http", authority = Uri.Authority(Uri.Host(config.getString("services.nifi-api.host")), port = config.getInt("services.nifi-api.port")), path = path)
  }

  //We provide a position here. Not sure we have to do that.
  //TODO  parentGroupId vs. clientId. What's going on here?
  def createProcessGroupJson(parentGroupId: String, name: String): String = {
    s"""{"revision":{"clientId":"$parentGroupId","version":0},"component":{"name":"$name","position":{"x":181,"y":140.5}}}"""
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
  def importTemplateIntoProcessGroup(processGroupId: String, templateId: String): Future[FlowDTO] = {
    HttpRequest(HttpMethods.POST, uri = nifiUri(Uri.Path(s"$apiPath/process-groups/${processGroupId}/template-instance")),
      entity = HttpEntity.apply(ContentTypes.`application/json`, importTemplateIntoProcessGroupJson(templateId))
    ).withResp(respEntity => Unmarshal(respEntity).to[String].map { str =>
      println(s"importTemplateIntoProcessGroup response as String is $str")
      JaxBConverters.JsonConverters.fromJsonString[FlowDTO](str)
    }

    )

  }

  //Same as above.  Unfortunately when converting to JaxB the config/properties end up empty, so we've been
  //forced to work with JsValues instead.
  def importTemplateIntoProcessGroupReturnsJsValue(processGroupId: String, templateId: String): Future[JsValue] = {
    HttpRequest(HttpMethods.POST, uri = nifiUri(Uri.Path(s"$apiPath/process-groups/${processGroupId}/template-instance")),
      entity = HttpEntity.apply(ContentTypes.`application/json`, importTemplateIntoProcessGroupJson(templateId))
    ).withResp(respEntity => Unmarshal(respEntity).to[JsValue]  )

  }

  def findClientId: Future[String] = {
    HttpRequest(HttpMethods.GET, uri = nifiUri(Uri.Path(s"$apiPath/flow/client-id"))).withResp(Unmarshal(_).to[String])
  }

  //TODO: at some point we could do text replace by using Framing (this article doesn't quite do that, but it's a start: https://stackoverflow.com/questions/40224457/reading-a-csv-files-using-akka-streams)
  //and we probably could send streaming text to the multipart/form-data code.  But that's for later.
  def replacement(templateFile: File,  replaceTemplateValues: String): Future[String] = {
    val r: Map[String, String] => String = replace(Misc.readText(ourTemplateFile.getPath), _)
    for {
      jsVal <- Unmarshal(replaceTemplateValues).to[JsValue]
    } yield r(jsVal.convertTo[Map[String, String]])
  }

  def uploadTemplate(parentProcessGroupId: String, text: String, filename: String): Future[String] = {
    for {
      templateUploadReq <- TemplateUploadViaMultipart.createRequest(
        nifiUri(Uri.Path(s"$apiPath/process-groups/${parentProcessGroupId}/templates/upload")),
        text, filename)
      xml: NodeSeq <- templateUploadReq.withResp(Unmarshal(_).to[NodeSeq])
    } yield (xml \ "template" \ "id").headOption.map{_.text.trim}.get
  }

  def createProcessGroup(parentProcessGroupId: String, processGroupName: String): Future[ProcessGroupEntity] = {
    HttpRequest(HttpMethods.POST, uri = nifiUri(Uri.Path(s"$apiPath/process-groups/${parentProcessGroupId}/process-groups")),
      entity = HttpEntity.apply(ContentTypes.`application/json`, createProcessGroupJson(parentProcessGroupId, processGroupName))
    ).withResp(respEntity =>   Unmarshal(respEntity).to[String].map(JaxBConverters.JsonConverters.fromJsonString[ProcessGroupEntity]) )

  }

  //TODO Get from case class or from JaxB.
  def componentStateUpdateJson(componentId: String, state: String, clientId: String): String = {
    s"""{"revision":{"clientId":"$clientId","version":0},"component":{"id":"$componentId","state":"$state"}}"""
  }

  def updateStateOfHttpContextMap(httpContextMapId: String, state: String, clientId: String): Future[String] = {
    HttpRequest(HttpMethods.PUT, uri = nifiUri(Uri.Path(s"$apiPath/controller-services/${httpContextMapId}")),
      entity = HttpEntity.apply(ContentTypes.`application/json`, componentStateUpdateJson(httpContextMapId, state, clientId))
    ).withResp(respEntity =>   Unmarshal(respEntity).to[String] )

  }  
  

  def findIdsOfHttpContextMaps(theBigJsValue: JsValue): Try[Set[String]] = {
    val tryOfVector: Try[Vector[Map[String, JsValue]]] = deep(theBigJsValue, List("flow", "processors")).flatMap{processorsValue: JsValue =>
      jsArrayToVector(processorsValue).flatMap{vec: Vector[JsValue] =>
        Misc.sequence(vec.map(processorJsValue => deep(processorJsValue, List("component", "config", "properties")).flatMap(asMap)  ))
      }
    }

    tryOfVector.map{vec =>
      vec.map(_.get("HTTP Context Map")).collect{ case Some(jsValue) => jsValue   }
    }.flatMap{jsValueIds =>
      Misc.sequence(jsValueIds.map(asString)).map(_.to[Set])
    }
  }


  val fut =
    for {
      replacedText <-  replacement(ourTemplateFile, replaceTemplateValues)
      templateId <- uploadTemplate(nifiRootProcessorGroupId, replacedText, ourTemplateFile.getName)
      clientId <-  findClientId
      processGroupEntity: ProcessGroupEntity <- createProcessGroup(nifiRootProcessorGroupId, "ourProcessGroup")
      jsValueForTemplateImport <- importTemplateIntoProcessGroupReturnsJsValue(processGroupEntity.getId, templateId)
      ids = findIdsOfHttpContextMaps(jsValueForTemplateImport).get //TODO Handle Try inside a function
      res <- Future.sequence(ids.map(id => updateStateOfHttpContextMap(id, "ENABLED", clientId)))

    } yield (res)


  println("result was\n" +
    await(fut))
  
  
  def await[T](future: Future[T], dur: FiniteDuration = 300.millis): T =  Await.result(future, 2000.millis)

  println("hello")
  System.exit(0)



}
