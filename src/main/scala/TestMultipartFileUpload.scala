package akka.http.scaladsl

import java.io.File

import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.util.ByteString

import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Source}
import com.typesafe.config.{ ConfigFactory, Config }

import scala.concurrent.Future

/**
 * See https://gist.github.com/jrudolph/08d0d28e1eddcd64dbd0
 * The following note is taken from that url:
 * To increase the size of file uploads, set this option in application.conf:
 * akka.http.server.parsing.max-content-length = 512m
 */

/*To increase the size of file uploads, set this option in application.conf:
akka.http.server.parsing.max-content-length = 512m
*/
object TestMultipartFileUpload extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off""")
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  val testFile = new File(args(0))

  def startTestServer(): Future[ServerBinding] = {
    import akka.http.scaladsl.server.Directives._

    val route: Route =
      path("upload") {
        entity(as[Multipart.FormData]) { (formdata: Multipart.FormData) ⇒
          val fileNamesFuture = formdata.parts.mapAsync(1) { p ⇒
            println(s"Got part. name: ${p.name} filename: ${p.filename}")

            println("Counting size...")
            @volatile var lastReport = System.currentTimeMillis()
            @volatile var lastSize = 0L
            def receiveChunk(counter: (Long, Long), chunk: ByteString): (Long, Long) = {
              val (oldSize, oldChunks) = counter
              val newSize = oldSize + chunk.size
              val newChunks = oldChunks + 1

              val now = System.currentTimeMillis()
              if (now > lastReport + 1000) {
                val lastedTotal = now - lastReport
                val bytesSinceLast = newSize - lastSize
                val speedMBPS = bytesSinceLast.toDouble / 1000000 /* bytes per MB */ / lastedTotal * 1000 /* millis per second */

                println(f"Already got $newChunks%7d chunks with total size $newSize%11d bytes avg chunksize ${newSize / newChunks}%7d bytes/chunk speed: $speedMBPS%6.2f MB/s")

                lastReport = now
                lastSize = newSize
              }
              (newSize, newChunks)
            }

            p.entity.dataBytes.runFold((0L, 0L))(receiveChunk).map {
              case (size, numChunks) ⇒
                println(s"Size is $size")
                (p.name, p.filename, size)
            }
          }.runFold(Seq.empty[(String, Option[String], Long)])(_ :+ _).map(_.mkString(", "))

          complete {
            fileNamesFuture
          }
        }
      }
    Http().bindAndHandle(route, interface = "localhost", port = 0)
  }

  def createEntity(file: File): Future[RequestEntity] = {
    require(file.exists())
    val formData =
      Multipart.FormData(
        Source.single(
          Multipart.FormData.BodyPart(
            "test",
            HttpEntity(MediaTypes.`application/octet-stream`, file.length(), FileIO.fromFile(file, chunkSize = 10)), // the chunk size here is currently critical for performance
            Map("filename" -> file.getName))))
    Marshal(formData).to[RequestEntity]
  }

  def createRequest(target: Uri, file: File): Future[HttpRequest] =
    for {
      e ← createEntity(file)
    } yield HttpRequest(HttpMethods.POST, uri = target, entity = e)

  try {
    val result =
      for {
        ServerBinding(address) ← startTestServer()
        _ = println(s"Server up at $address")
        port = address.getPort
        target = Uri(scheme = "http", authority = Uri.Authority(Uri.Host("localhost"), port = port), path = Uri.Path("/upload"))
        req ← createRequest(target, testFile)
        _ = println(s"Running request, uploading test file of size ${testFile.length} bytes")
        response ← Http().singleRequest(req)
        responseBodyAsString ← Unmarshal(response).to[String]
      } yield responseBodyAsString

    result.onComplete { res ⇒
      println(s"The result was $res")
      //system.shutdown()
    }

 /*   system.scheduler.scheduleOnce(60.seconds) {
      println("Shutting down after timeout...")
      system.shutdown()
    }*/
  } catch {
    case err: Throwable ⇒
      println(s"got error $err")
  }
}