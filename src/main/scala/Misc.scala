

object Misc {

  def readText(fileName: String): String = {
    import scala.io.Source
    val source = Source.fromFile(fileName)
    try source.getLines().mkString("\n") finally source.close
  }

  def replaceText(start: String, end: String)(text: String, replaceValues: Map[String, String]): String = {
    replaceValues.foldLeft(text){ (text, keyAndValue) =>
      keyAndValue match {
        case (variable, theReplacement) =>
          text.replaceAll( start + variable + end, theReplacement)
      }

    }

  }

}
