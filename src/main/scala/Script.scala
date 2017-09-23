
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
import com.typesafe.config.ConfigFactory
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
import CollectionHelp._
import spray.json._

import scala.util.{Failure, Try}
import scala.xml.NodeSeq

//TODO we've arbitrarily specified component positions in some places.
//With importTemplateIntoProcessGroupJson we had to, otherwise we'd get this error:400 Bad Request. Response body was The origin position (x, y) must be specified.
//So what do we do about component positions?
object Script extends App with Protocol {

  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()
  val logger: LoggingAdapter = Logging(system, getClass)

  lazy val config = ConfigFactory.load()
  val apiPath = config.getString("services.nifi-api.path")
  val templateDir = config.getString("template.directory")
  val replace: (String, Map[String, String]) => String = Misc.replaceText("\\{\\{", "}}") _

  implicit val inputDataFormat = jsonFormat4(InputData.apply)


  //The value for my local nifi instance si 5cb229a2-015e-1000-af7e-47911f0b10d6
  val inputFilename = args(0)



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
    //Note: Failure to discard the HttpEntity on an unit return will cause Akka http to do back-pressure, and we don't want that.
    //Currently we solve the problem by expecting callers of withResp to use unitReturnAndDiscardBytes
    //when they want to return unit.
    // We could instead have added a (implicit CT: ClassTag[T]) to this signature, and used
    //val discardEntity = returnClass.getSimpleName == "void"
    //to tell our getIt function to discard the entity automatically.
    def withResp[T](f: HttpEntity => Future[T], acceptableStatus: Set[Int] = Set(200, 201)): Future[T] = {
      getIt(req, acceptableStatus)(f)
    }
  }


  /**
   * From http://doc.akka.io/docs/akka-http/current/scala/http/implications-of-streaming-http-entity.html
   * Consuming (or discarding) the Entity of a request is mandatory! If accidentally left neither consumed or discarded Akka HTTP will assume the incoming data should remain back-pressured, and will stall the incoming data via TCP back-pressure mechanisms. A client should consume the Entity regardless of the status of the HttpResponse.
   */
  val unitReturnAndDiscardBytes: HttpEntity => Future[Unit] = {respEntity => respEntity.dataBytes.runWith(Sink.ignore) ;  Future.successful(()) }

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

  def findClientId: Future[String] = {
    HttpRequest(HttpMethods.GET, uri = nifiUri(Uri.Path(s"$apiPath/flow/client-id"))).withResp(Unmarshal(_).to[String])
  }

  def uploadTemplate(parentProcessGroupId: String, text: String, filename: String): Future[String] = {
    for {
      templateUploadReq <- TemplateUploadViaMultipart.createRequest(
        nifiUri(Uri.Path(s"$apiPath/process-groups/${parentProcessGroupId}/templates/upload")),
        text, filename)
      xml: NodeSeq <- templateUploadReq.withResp(Unmarshal(_).to[NodeSeq])
    } yield (xml \ "template" \ "id").headOption.map{_.text.trim}.get
  }

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

  //short circuit upon the first failure
  def runSequentially[K,V](args: List[K], f: K => Future[V], accum: List[V] = List()  ): Future[List[V]] = {

    args match {
      case Nil => Future.successful(accum.reverse)
      case k :: ks =>
        f(k).flatMap{v =>
          runSequentially(ks, f, v :: accum)
        }
    }

  }


  /**
   * Run in parallel, and if one or more Future fails, others may still succeed. The result of running an f against
   * a K will be associated with that K, and a Vector of the results is returned. If there was one or more failures,
   * result will be a Vector of K -> Throwable pairs. If all succeeded, the result is a Vector of K -> V
   */
  def runManyGeneric[K, V](args: Vector[K], f: K => Future[V]): Future[Either[ Vector[(K,Throwable)], Vector[(K,V)]  ]] = {
    Future.sequence(args.map(arg =>
      f(arg).map{v =>
        Right(arg ->  v)
      }.recover{
        case ex =>
          logger.info(s"failure. is is ${arg} and ex is $ex")
          Left(arg -> ex)
      }

    )).map{vec: Vector[Either[(K, Throwable), (K,V)]] => Either.sequence(vec)   }
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
    }.map(_.filter(ci => ci.name != "Dummy" && ci.state != "RUNNING"))


    Misc.tryMap2(tryOfHttpContextMapIds, tryOfComponentsThatNeedToBeEnabled)( (_,_) )

  }

  def findHttpContextMapIds(flow: NifiFlow): Set[String] = {
    flow.processors.map(
      _.component.config.properties.get("HTTP Context Map")
    ).collect{ case Some(JsString(id)) =>
      id
    }.toSet

  }


  //Create process groups in parallel
/*  val fut = Unmarshal(Misc.readText(inputFilename)).to[Vector[InputData]].flatMap{several =>
    runMany(several, createAndStartProcessGroup, "Succeeded in creating processGroups for these configurations", "failed to create a process group for these configurations")
  }*/
  //  OR
  //Create process groups one after the other. Might be easier for ops to handle failures this way,
  //at least until our logging is really good.
  val fut = Unmarshal(Misc.readText(inputFilename)).to[Vector[InputData]].flatMap { several =>
    runSequentially(several.toList, createAndStartProcessGroup)
  }


  //TODO: I'd like to stream data from our template file, use Framing to cut the file into lines, do text replace on the streaming lines,
  //and stream the replaced lines as we upload.  But don't know how to do that now.
  //This article doesn't quite do that, but at least it shows the use of framing: https://stackoverflow.com/questions/40224457/reading-a-csv-files-using-akka-streams)
  def createAndStartProcessGroup(inputData: InputData): Future[Unit] = {
    val replacedText = replace(Misc.readText(s"$templateDir/${inputData.templateFileName}"), inputData.templateReplacementValues)
    for {

      templateId <- uploadTemplate(inputData.nifiRootProcessorGroupId, replacedText, inputData.templateFileName)

      clientId <-  findClientId

      processGroupEntity: ProcessGroupEntity <- createProcessGroup(inputData.nifiRootProcessorGroupId, inputData.processorGroupName, clientId)

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

  logger.info("result was\n" +
    await(fut))
  
  
  def await[T](future: Future[T], dur: FiniteDuration = 2000.millis): T =  Await.result(future, dur)




}
