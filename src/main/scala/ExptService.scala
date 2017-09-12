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
import org.apache.nifi.web.api.entity.{SnippetEntity, SearchResultsEntity}

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

  def postSnippet(str: String): Future[HttpResponse] = {
    //import akka.http.scaladsl.model.HttpRequest
    //Note: I could also use def HttpEntity.apply(contentType: ContentType, data: ByteString)
    nifiApiRequest(akka.http.scaladsl.model.HttpRequest.apply(method = HttpMethods.POST, uri = s"$apiPath/snippets", entity = HttpEntity.apply(ContentTypes.`application/json`, str)))
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
            val templateText = Misc.readText("src/main/resources/nifi-templates/simpleTemplate3.xml")
            val templateFromXml: TemplateDTO = JaxBConverters.XmlConverters.fromXmlString[TemplateDTO](templateText)

            //If we don't put our templateFromXml's snippet (which is a SnippetDTO, not a SnippetEntity) inside a SnippetEntity, we get this error message: [
            //com.sun.istack.SAXException2: unable to marshal type "org.apache.nifi.web.api.dto.FlowSnippetDTO" as an element because it is missing an @XmlRootElement annotation]
            //But unfortunately, what we get from our template is a templateDTO, which owns a FlowSnippetDTO.
            //And the NIFI POST /nifi-api/snippet endpoint requires a SnippetEntity, and SnippetEntity needs a SnippetDTO, not a FlowSnippetDTO!
/*
            val snippetEntity = new SnippetEntity
            snippetEntity.setSnippet(templateFromXml.getSnippet)
            //would like to do this, but the above line doesn't compile!
            val jsonString = JaxBConverters.JsonConverters.toJsonString(snippetEntity)
*/

            //We're just settling for compilation here.  We know we need to send a SnippetEntity, not a TemplateDTO
            //We'll receive this error message: Message body is malformed. Unable to map into expected format.
            val jsonString = JaxBConverters.JsonConverters.toJsonString(templateFromXml)

            logger.info(s"json string we're sending is $jsonString")

            complete {
              postSnippet(jsonString).flatMap { resp: HttpResponse =>
                //TODO send response based on status.
                logger.info("snippet status is " + resp.status)
                pullStringFromEntity(resp.entity)(entityToStrictTimeout).map { str =>
                  //HEY! Maybe we should send xml instead of json!!!
                  logger.info(s"string value of response to our snippet post attempt is: \n$str")
                  val jsValue: JsValue = JsonParser(str)
                  val stringKeysAndJsValuesMap: Map[String, spray.json.JsValue] = jsValue.asJsObject.fields
                  logger.info(s"HO Ho HO return is ${stringKeysAndJsValuesMap}")
                  OK -> stringKeysAndJsValuesMap
                }
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
