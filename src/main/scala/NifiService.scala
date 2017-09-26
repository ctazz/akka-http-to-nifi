import akka.actor.ActorSystem
import akka.event.{LoggingAdapter, Logging}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.Http

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import scala.concurrent.{ExecutionContextExecutor, Future}


import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._


import Utils._


trait NifiService extends Protocol {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer

  def config: Config
  val logger: LoggingAdapter

  lazy val nifiInteractions = new NifiInteractions(config, logger)

  val routes: Route = {
    logRequestResult("Nifi service") {

      //Not sure this is all that RESTful
      path("processor-groups") {
        (post & entity(as[Vector[InputData]])) {inputDataVec =>
            complete {
              runSequentially(inputDataVec.toList, nifiInteractions.createAndStartProcessGroup).flatMap(_ => Future.successful(Created -> "success")).recover {

                case ABadRequest(msg) => BadRequest -> msg

                case ex => InternalServerError -> s"Error: ${ex.getMessage}"
              }
            }
          }
      }

    }

  }


}

object NifiServiceImpl extends NifiService with App  {

  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorMaterializer()

  override val config = ConfigFactory.load()
  override val logger = Logging(system, getClass)

  val host = config.getString("http.interface")
  val port = config.getInt("http.port")

  val bindingFuture = Http().bindAndHandle(routes, host, port)
  logger.info(s"starting app on host $host and port $port")


}
