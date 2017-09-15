

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.stream.ActorMaterializer
import spray.json.DefaultJsonProtocol._

import akka.http.scaladsl.unmarshalling.Unmarshal

import spray.json._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

object SprayJsonExpt extends App {

  val source = """{ "some": "JSON source" }"""

  val jsonAst: JsValue =  JsonParser(source)
  println(jsonAst)

  assert(jsonAst.asJsObject.fields.get("some") == Some(JsString("JSON source")))

  val x: JsValue = List(1, 2, 3).toJson

  //TODO If I want to go from scala object to JSON, I should begin with a Map[String, JsValue], and inner objects
  //should be JsObjects
  //Error:(18, 119) Cannot find JsonWriter or JsonFormat type class for scala.collection.immutable.Map[String,Object]
  //scala.collection.immutable.Map("hey" -> "bb", "x" -> "y", "inner" -> scala.collection.immutable.Map("ho" -> "yee")).toJson
/*  println(
  scala.collection.immutable.Map("hey" -> "bb", "x" -> "y", "inner" -> scala.collection.immutable.Map("ho" -> "yee")).toJson
  )*/

  val configMap: JsValue = Map("knownBrokers" -> "127.0.0.1:9093,127.0.0.1:9094", "listeningPort" -> "9010", "allowedPaths" -> "/data").toJson
  println(s"configMap is ${configMap}")

  import spray.json.JsObject
  //@@@Pulling out inner data from a JsValue.
  //1) We could use JsonParser and then pattern match on the inner object,
  val jsonString = """{"knownBrokers":"127.0.0.1:9093,127.0.0.1:9094","listeningPort":"9010","allowedPaths":"/data", "obj":{"x":7}}"""
  val complex: Map[String, JsValue] = JsonParser(jsonString).asJsObject.fields
  complex.get("obj").get match {
    case JsObject(xx) => println(s"we got a Map. It's $xx")
  }

  //2) We could turn the inner object back into a String and then use JsonParser again
  assert(JsonParser(complex.get("obj").get.toString).asJsObject.fields == Map("x" -> JsNumber(7)))

  //3) And we could use Unmarshall
  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()
  val fut: Future[Map[String, JsValue]] =   Unmarshal(jsonString).to[Map[String, JsValue]]
  println(s"unmarshalled is $fut")
  val innerFut = fut.flatMap{xx: Map[String, JsValue] => Unmarshal( xx.get("obj").get.toString).to[JsObject]   }

  println("inner " + await(innerFut).fields)


  val replace: (String, Map[String, String]) => String = Misc.replaceText("\\{\\{", "}}") _

  val textToReplace = """
Hello {{knownBrokers}} Hello. {{listeningPort}}
{{knownBrokers}}"""


  val expected = """
Hello 127.0.0.1:9093,127.0.0.1:9094 Hello. 9010
127.0.0.1:9093,127.0.0.1:9094"""

  val simple_NoInnerObj = """{"knownBrokers":"127.0.0.1:9093,127.0.0.1:9094","listeningPort":"9010","allowedPaths":"/data"}"""
  val futureReplaced = Unmarshal(simple_NoInnerObj).to[Map[String, String]].map{replace(textToReplace, _)   }

  //println("replaced is\n" + await(futureReplaced))
  assert(await(futureReplaced) == expected)


  def await[T](future: Future[T], dur: FiniteDuration = 300.millis): T =  Await.result(future, 2000.millis)




  //regexp surprise!
  assert(
    "[[xxx]]".replaceAll("[[xxx]]", "hello")  == "[[hellohellohello]]"
  )

  assert(
  "[[xxx]]".replaceAll("\\[\\[xxx\\]\\]", "hello") == "hello"
  )


  assert(
    "{{xxx}}".replaceAll("\\{\\{xxx\\}\\}", "hello") == "hello"
  )

  assert(
    "sss!!xxx!!".replaceAll("!!xxx!!", "hello") == "ssshello"
  )

  assert(
  "xx".replaceAll("xx", "yay") == "yay"
  )




}
