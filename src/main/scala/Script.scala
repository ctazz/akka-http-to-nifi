
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

import akka.http.scaladsl.model._
import akka.http.scaladsl.client.RequestBuilding._
import com.typesafe.config.ConfigFactory
import org.apache.nifi.web.api.entity.ProcessGroupEntity
import scala.concurrent.{Await, Future, ExecutionContextExecutor}
import java.io.File
import akka.http.scaladsl.marshalling.Marshal

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._

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

  val apiPath = config.getString("services.nifi-api.path")

  //TODO This would be re-usable if we passed in some values for the Multipar.FormData.BodyPart
  object TemplateUploadViaMultipart {
    def createEntity(file: File): Future[RequestEntity] = {
      require(file.exists())
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

    def createRequest(target: Uri, file: File): Future[HttpRequest] =
      for {
        e ← createEntity(file)
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
  def importTemplateIntoProcessGroup(processGroupId: String, templateId: String): Future[String] = {
    HttpRequest(HttpMethods.POST, uri = nifiUri(Uri.Path(s"$apiPath/process-groups/${processGroupId}/template-instance")),
      entity = HttpEntity.apply(ContentTypes.`application/json`, importTemplateIntoProcessGroupJson(templateId))
    ).withResp(Unmarshal(_).to[String] )

  }

  def findClientId: Future[String] = {
    HttpRequest(HttpMethods.GET, uri = nifiUri(Uri.Path(s"$apiPath/flow/client-id"))).withResp(Unmarshal(_).to[String])
  }

  def uploadTemplate(parentProcessGroupId: String, templateFile: File): Future[String] = {
    for {
      templateUploadReq <- TemplateUploadViaMultipart.createRequest(
        nifiUri(Uri.Path(s"$apiPath/process-groups/${parentProcessGroupId}/templates/upload")),
        templateFile)
      xml: NodeSeq <- templateUploadReq.withResp(Unmarshal(_).to[NodeSeq])
    } yield (xml \ "template" \ "id").headOption.map{_.text.trim}.get
  }

  def createProcessGroup(parentProcessGroupId: String, processGroupName: String): Future[ProcessGroupEntity] = {
    HttpRequest(HttpMethods.POST, uri = nifiUri(Uri.Path(s"$apiPath/process-groups/${parentProcessGroupId}/process-groups")),
      entity = HttpEntity.apply(ContentTypes.`application/json`, createProcessGroupJson(parentProcessGroupId, processGroupName))
    ).withResp(respEntity =>   Unmarshal(respEntity).to[String].map(JaxBConverters.JsonConverters.fromJsonString[ProcessGroupEntity]) )

  }

  val fut =
    for {
      templateId <- uploadTemplate(nifiRootProcessorGroupId, ourTemplateFile)
      clientId <-  findClientId
      processGroupEntity: ProcessGroupEntity <- createProcessGroup(nifiRootProcessorGroupId, "ourProcessGroup")
      importTemplateResponse <- importTemplateIntoProcessGroup(processGroupEntity.getId, templateId)
    } yield (importTemplateResponse)


  println("result was\n" +
    await(fut))
  
  
  def await[T](future: Future[T], dur: FiniteDuration = 300.millis): T =  Await.result(future, 2000.millis)

  println("hello")
  System.exit(0)



}
