
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
import scala.concurrent.{Await, Future, ExecutionContextExecutor}
import java.io.File
import akka.http.scaladsl.marshalling.Marshal

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

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

  def nifiUri(path: Uri.Path): Uri = {
    Uri(scheme = "http", authority = Uri.Authority(Uri.Host(config.getString("services.nifi-api.host")), port = config.getInt("services.nifi-api.port")), path = path)
  }

  //val xmlUploadTarget = Uri(scheme = "http", authority = Uri.Authority(Uri.Host(config.getString("services.nifi-api.host")), port = config.getInt("services.nifi-api.port")), path = Uri.Path(s"$apiPath/process-groups/${nifiRootProcessorGroupId}/templates/upload"))
  //s"$apiPath/flow/client-id")


  val fut =
    for {
      req <- TemplateUploadViaMultipart.createRequest(nifiUri(Uri.Path(s"$apiPath/process-groups/${nifiRootProcessorGroupId}/templates/upload")), ourTemplateFile)
      response <- Http().singleRequest(req)
      responseBodyAsString ← Unmarshal(response).to[String]
      _ = println(s"template upload response is ${responseBodyAsString}")
    } yield(responseBodyAsString)

  Thread.sleep(5000)


  await(fut)
  
  
  def await[T](future: Future[T], dur: FiniteDuration = 300.millis): T =  Await.result(future, 300.millis)

  println("hello")
  //System.exit(0)



}
