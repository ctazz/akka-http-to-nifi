

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import spray.json._
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

}
