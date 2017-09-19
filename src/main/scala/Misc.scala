import scala.util.{Failure, Success, Try}

object Misc {

  //TODO: Far better to read text using Akka streaming io, although the disadvantage here
  //shouldn't be to bad for small files.
  def readText(fileName: String): String = {
    import scala.io.Source
    val source = Source.fromFile(fileName)
    try source.getLines().mkString("\n") finally source.close
  }

  //This is a quick and dirty way to do text replace. If the text String is long, it would be very inefficient.
  def replaceText(start: String, end: String)(text: String, replaceValues: Map[String, String]): String = {
    replaceValues.foldLeft(text){ (text, keyAndValue) =>
      keyAndValue match {
        case (variable, theReplacement) =>
          text.replaceAll( start + variable + end, theReplacement)
      }

    }

  }


  //TODO Use cats library, or at least CanBuildFrom
  def sequence[T](seq: Vector[Try[T]]): Try[Vector[T]] = seq.foldLeft(Success(Vector.empty[T]): Try[Vector[T]]){ (acc: Try[Vector[T]], next: Try[T]) =>

    acc match {
      case Failure(ex) => Failure(ex)
      case Success(vec) => next match {
        case Failure(ex) => Failure(ex)
        case Success(thisOne) => Success(vec :+ thisOne)
      }
    }

  }

  def tryMap2[A,B,Z](fa: Try[A], fb: Try[B] )(f: (A,B) => Z): Try[Z] = {
    for {
      a <- fa
      b <- fb
    } yield f(a,b)
  }


}

case class ComponentInfo(id: String, name: String, state: String)
