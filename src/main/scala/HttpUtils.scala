import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Sink

import scala.concurrent.{ExecutionContextExecutor, Future}

object HttpUtils {

  sealed trait HttpHelper {

    def handleBadStatus[T](resp: HttpResponse): Future[T]

    def getIt[T](httpRequest: HttpRequest, acceptableStatus: Set[Int] = Set(200, 201))(f: HttpEntity => Future[T]): Future[T]
  }

  implicit def makeHttpHelper(implicit system: ActorSystem, executor: ExecutionContextExecutor, materializer: Materializer): HttpHelper = {

    new HttpHelper {

      override def handleBadStatus[T](resp: HttpResponse): Future[T] = {
        Unmarshal(resp.entity).to[String].flatMap { bodyAsString =>
          Future.failed(new RuntimeException(s"failed with status ${resp.status}. Response body was $bodyAsString"))
        }
      }

      override def getIt[T](httpRequest: HttpRequest, acceptableStatus: Set[Int])(f: Function1[HttpEntity, Future[T]]): Future[T] = {
        Http().singleRequest(httpRequest).flatMap { resp: HttpResponse =>
          resp.status.intValue match {
            case status if acceptableStatus.contains(status) => f(resp.entity)
            case other => handleBadStatus[T](resp)
          }

        }
      }

    }

  }

  implicit class HttpRequestOps(val req: HttpRequest) extends AnyVal {

    def withResp[T](f: HttpEntity => Future[T], acceptableStatus: Set[Int] = Set(200, 201))(implicit helper: HttpHelper): Future[T] = {
      helper.getIt(req, acceptableStatus)(f)
    }

  }


  /**
   * From http://doc.akka.io/docs/akka-http/current/scala/http/implications-of-streaming-http-entity.html
   * Consuming (or discarding) the Entity of a request is mandatory! If accidentally left neither consumed or discarded Akka HTTP will assume the incoming data should remain back-pressured, and will stall the incoming data via TCP back-pressure mechanisms. A client should consume the Entity regardless of the status of the HttpResponse.
   * And note: the same goes for responses, and that's why we have this function.
   */
  def unitReturnAndDiscardBytes(respEntity: HttpEntity)(implicit materializer: akka.stream.Materializer):Future[Unit] = {
    respEntity.dataBytes.runWith(Sink.ignore) ;  Future.successful(())
  }


}
