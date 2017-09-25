import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.unmarshalling.Unmarshal


import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._


import Utils._

object TheScript extends NifiInteractions with App {

  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorMaterializer()
  override val logger = Logging(system, getClass)

  override lazy val config = ConfigFactory.load()

  //The value for my local nifi instance si 5cb229a2-015e-1000-af7e-47911f0b10d6
  val inputFilename = args(0)

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

  //TODO Perhaps have different messages based on what the Exception is
  val message: Future[String] = fut.map{_ => "ok"}.recover{
    case ex => s"Failure while trying to set up Nifi processor group(s). Exception was $ex"
  }


  logger.info("result was\n" +
    await(message))


  def await[T](future: Future[T], dur: FiniteDuration = 2000.millis): T =  Await.result(future, dur)



}
