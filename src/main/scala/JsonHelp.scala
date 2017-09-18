import spray.json.{JsString, JsArray, JsNull, JsObject, JsValue}

import scala.util.{Failure, Success, Try}

object JsonHelp {


  def deep(parent: JsValue, keys: List[String], prevKey: Option[String] = None): Try[JsValue] = {
    keys match {

      case Nil => Success(parent)

      case head :: tail => parent match {

        case JsObject(fields) => fields.get(head) match {
          case Some(jsValue) => deep(jsValue, tail, Some(head))
          case None => Failure(new JsonReadError(s"key $head is empty"))
        }

        case JsNull => Failure(JsonReadError(s"key $head is null"))

        case other =>
          val msg = prevKey.map { k => s"jsValue associated with key '$k' is not a JsObject. Instead it is a ${other.getClass}" }.getOrElse {
            "initial parent object is not a JsObject"
          }
          Failure(new JsonReadError(msg))
      }


    }
  }

  def jsArrayToVector(value: JsValue): Try[Vector[JsValue]] = {
    value match {
      case JsArray(vec) => Success(vec)
      case other => Failure(new JsonReadError("Not a JsArray"))
    }
  }

  def asMap(jsValue: JsValue): Try[Map[String, JsValue]] = jsValue match {
    case JsObject(fields) => Success(fields)
    case other => Failure(new JsonReadError("Not a JsObject"))
  }

  def asString(jsValue: JsValue): Try[String] = jsValue match {
    case JsString(str) => Success(str)
    case other => Failure(new JsonReadError("Not a JsString"))
  }



}
