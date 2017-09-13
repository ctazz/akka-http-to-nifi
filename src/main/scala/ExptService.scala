import akka.actor.ActorSystem
import akka.event.{LoggingAdapter, Logging}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import org.apache.nifi.web.api.dto.TemplateDTO

//import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.{StringReader, IOException}
import org.apache.nifi.web.api.entity.{CreateTemplateRequestEntity, TemplateEntity, SnippetEntity, SearchResultsEntity}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.math._
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._

//***If I decide to use JaxB, I should take a look at these articles, especially where the second article discusses
//JSONJAXBContext from Jersey's JSON library.
//http://www.vogella.com/tutorials/JAXB/article.html
//http://krasserm.blogspot.com/2012/02/using-jaxb-for-xml-and-json-apis-in.html
//And then I need to import this dependency: //org.apache.nifi nifi-client-dto 1.3.0
/*import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBException
import javax.xml.bind.Marshaller
import javax.xml.bind.Unmarshaller
import com.sun.jersey.api.json._*/

//Trying out spray json
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import spray.json._

//!!!For posts we can do, if necessary, DTO     ->   String      ->          Json
//                                            via jaxb        via spray-json




trait NifiService {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer

  def config: Config
  val logger: LoggingAdapter

  val entityToStrictTimeout: FiniteDuration

  lazy val nifiApiConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnection(config.getString("services.nifi-api.host"), config.getInt("services.nifi-api.port"))

  def nifiApiRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(nifiApiConnectionFlow).runWith(Sink.head)

  def apiPath = config.getString("services.nifi-api.path")

  def searchResults(arg: String): Future[Either[String, ResponseEntity]] = {

    nifiApiRequest(RequestBuilding.Get(s"$apiPath/flow/search-results?q=$arg")).map{resp: HttpResponse =>
      resp.status match {
        case OK =>
          Right(resp.entity)
        case x => Left(s"failed with status $x. resp was $resp")
      }
    }

  }

  def handleBadStatus[T](resp: HttpResponse): Future[T] = {
    Unmarshal(resp.entity).to[String].flatMap { bodyAsString =>
      Future.failed(new RuntimeException(s"failed with status ${resp.status}. resp was $bodyAsString"))
    }
  }

  def clientId: Future[String] = {
    nifiApiRequest(RequestBuilding.Get(s"$apiPath/flow/client-id")).flatMap{resp: HttpResponse =>
      resp.status match {
        case OK =>
          Unmarshal(resp.entity).to[String]
        case x =>
          handleBadStatus[String](resp)
      }
    }
  }

  //TODO  At some point may need a more performant or more standardized text replacement facility
  def replaceText(text: String, replaceValues: Map[String, String]): String = {
    //logger.info(s"map is $replaceValues")
    val answer = replaceValues.foldLeft(text){ (text, keyAndValue) =>
        keyAndValue match {
          case (variable, theReplacement) =>
            text.replaceAll(variable, theReplacement)
        }

    }

    logger.info(s"!!!!!replaced text is $answer")
    answer
  }

/*  def postSnippet(str: String): Future[HttpResponse] = {
    //import akka.http.scaladsl.model.HttpRequest
    //Note: I could also use def HttpEntity.apply(contentType: ContentType, data: ByteString)
    nifiApiRequest(akka.http.scaladsl.model.HttpRequest.apply(method = HttpMethods.POST, uri = s"$apiPath/snippets", entity = HttpEntity.apply(ContentTypes.`application/json`, str)))
  }*/

  //TODO Get rid of all the copy-paste
  def postSnippet(str: String): Future[SnippetEntity] = {
    nifiApiRequest(akka.http.scaladsl.model.HttpRequest.apply(method = HttpMethods.POST, uri = s"$apiPath/snippets", entity = HttpEntity.apply(ContentTypes.`application/json`, str))).flatMap{resp =>
      resp.status match {
        case OK | Created =>
          Unmarshal(resp.entity).to[String].map{str =>
            JaxBConverters.JsonConverters.fromJsonString[SnippetEntity](str)

          }
        case other => handleBadStatus[SnippetEntity](resp)

      }
    }

  }

  def postTemplate(body: String, parentProcessGroupId: String): Future[TemplateEntity] = {

    logger.info(s"in postTemplate, body is $body and parentProcessGroupId is $parentProcessGroupId")

    nifiApiRequest(akka.http.scaladsl.model.HttpRequest.apply(method = HttpMethods.POST, uri = s"$apiPath/process-groups/$parentProcessGroupId/templates", entity = HttpEntity.apply(ContentTypes.`application/json`, body))).flatMap { resp =>
      resp.status match {
        case OK | Created =>
          Unmarshal(resp.entity).to[String].map{str =>
            println(s"entity for nifi-api template response is $str")
            JaxBConverters.JsonConverters.fromJsonString[TemplateEntity](str)
          }
        case other => handleBadStatus[TemplateEntity](resp)

      }
    }

  }

  def makeCreateTemplateRequestEntity(name: String, description: String, snippetId: String): String = {
    val ent = new CreateTemplateRequestEntity
    ent.setName(name)
    ent.setDescription(description)
    ent.setSnippetId(snippetId)

    JaxBConverters.JsonConverters.toJsonString(ent)
  }


  //TODO Turns out we don't need these conversions from ResponseEntity to String. We can just use Unmarshal(responseEntity).to[String]
  //This also means we don't need our entityToStrictTimeout
  def pullByteString(entity: ResponseEntity)(implicit timeout: FiniteDuration): Future[ByteString] = entity.toStrict(timeout).map { _.data }
  def stringFromByteString(byteString: ByteString): String = byteString.utf8String
  def s(futByteString: Future[ByteString]): Future[String] = futByteString.map(stringFromByteString)

  def pullStringFromEntity(entity: ResponseEntity)(implicit timeout: FiniteDuration): Future[String] = s( pullByteString(entity))


  val routes: Route = {
    logRequestResult("Nifi service") {
          //curl -i -H "Accept: application/json"  -X GET   http://localhost:9000/search/hey
          //When we return (OK -> json), we can return either Map[String,spray.json.JsValue] (which is JsonParser(str).asJsObject.fields)
          //or simply a Jsvalue (as with, for example, JsonParser(str)
        path("search" / Segment) { searchString =>
          get {
            complete {
              searchResults(searchString).flatMap[ToResponseMarshallable]{
                case Right(responseEntity) =>

                  //pullStringFromEntity(responseEntity)(entityToStrictTimeout).map{str =>
                  Unmarshal(responseEntity).to[String].map{str =>
                    val jsValue: JsValue = JsonParser(str)
                    val stringKeysAndJsValuesMap:  Map[String,spray.json.JsValue] = jsValue.asJsObject.fields
                    logger.info(s"HO Ho HO return is ${stringKeysAndJsValuesMap}")
                    OK -> stringKeysAndJsValuesMap
                  }

                    //This alternative also works:
/*
                   Unmarshal(responseEntity).to[Map[String, JsValue]].map{theMap: Map[String,spray.json.JsValue] =>
                    logger.info(s"HO Ho HO entity returned from NIFI is ${theMap}")
                    val processorResults = theMap.get("searchResultsDTO").get.asJsObject.fields.get("processorResults")
                    logger.info(s"processorResults are $processorResults")
                    processorResults.get match {
                    case JsArray(values) => logger.info(s"array contents are $values")
                    case other => logger.error(s"got this object $other ")
                    }

                    OK -> theMap
                  }*/


                case Left(errorMessage) => Future(BadRequest -> errorMessage)

              }
            }
          }
        } ~
        path("postsnippet") { //curl --include --request POST -H Topic:thetopic -H 'Content-type: application/json'  --data '[{"key":"val", "keyxx":"valuex"}, {"keykey":"v", "keydddd":"dddd"}]'  -X  POST http://localhost:9000/postsnippet
                      //Remember to look at this example: https://community.hortonworks.com/content/kbentry/87160/creating-nifi-template-via-rest-api.html
          post {
            //todo. Get these from posted entity
            val assumedParentGroupId = "5cb229a2-015e-1000-af7e-47911f0b10d6"
            val assumedProcessGroupId = "7672dde2-015e-1000-6d83-b90563f36023"

            val jsonString = Misc.readText("src/main/resources/nifi-templates/emptySnippetReplacable.json")

            complete {
              //Doesn't handle versioning yet
              (for {
                (cid, replacedJson) <- clientId.map(theClientId => (
                  theClientId,
                  replaceText(jsonString, Map("\\{parentGroupId\\}" -> assumedParentGroupId,
                  "\\{processGroupId\\}" -> assumedProcessGroupId, "\\{clientId\\}" ->  theClientId  ))
                  )
                )
                snippetEntity: SnippetEntity <- postSnippet(replacedJson)
                templateEntity: TemplateEntity <-  postTemplate(
                  makeCreateTemplateRequestEntity("theNewTemplateName", "some description", snippetEntity.getSnippet.getId),
                  assumedParentGroupId
                )
              } yield (templateEntity)).map{templateEntity: TemplateEntity =>
               OK -> JsonParser(
                 s"""
                   |{"templateId" : "${templateEntity.getTemplate.getId}"}
                 """.stripMargin)
              }

            }

          }
        }



    }

  }


}

object ExptService extends NifiService with App  {

  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorMaterializer()

  override val config = ConfigFactory.load()
  override val logger = Logging(system, getClass)


  override val entityToStrictTimeout = 300.millis

  val host = config.getString("http.interface")
  val port = config.getInt("http.port")

  val bindingFuture = Http().bindAndHandle(routes, host, port)
  logger.info(s"starting app on host $host and port $port")


}
