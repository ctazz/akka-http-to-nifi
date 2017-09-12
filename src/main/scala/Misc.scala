

object Misc {

  def readText(fileName: String): String = {
    import scala.io.Source
    val source = Source.fromFile(fileName)
    try source.getLines().mkString("\n") finally source.close
  }

}
