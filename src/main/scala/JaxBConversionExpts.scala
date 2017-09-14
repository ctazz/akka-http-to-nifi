
import java.io.{StringReader, ByteArrayOutputStream}
import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBException
import javax.xml.bind.Marshaller
import javax.xml.bind.Unmarshaller



import scala.reflect.ClassTag

//import org.apache.nifi.web.api.dto._
//import org.apache.nifi.web.api.dto._

import org.apache.nifi.web.api.dto._
import org.apache.nifi.web.api.dto.search._
import org.apache.nifi.web.api.entity._


/**
 * See //http://www.vogella.com/tutorials/JAXB/article.html
 http://krasserm.blogspot.com/2012/02/using-jaxb-for-xml-and-json-apis-in.html
 especially where the second article discusses JSONJAXBContext from Jersey's JSON library.
 */

object JaxBConversionExpts  extends  App {

  def readText(fileName: String): String = {
    import scala.io.Source
    val source = Source.fromFile(fileName)
    try source.getLines().mkString("\n") finally source.close
  }


  val searchResultsEntity: SearchResultsEntity = new SearchResultsEntity
  val searchResultsDto: SearchResultsDTO = new SearchResultsDTO
  searchResultsEntity.setSearchResultsDTO(searchResultsDto)
  //searchResultsDto.set

/*  val config = JSONConfiguration.natural().rootUnwrapping(false).build()
  val context = new JSONJAXBContext(config, classOf[SearchResultsEntity], classOf[TemplateEntity])

  val baos: ByteArrayOutputStream = new ByteArrayOutputStream
  context.createJSONMarshaller().marshallToJSON(searchResultsEntity, baos)
  val text = baos.toString("UTF-8")

  //val text = JaxBConverters.fromJson(searchResultsEntity)
  println(text)
  val x: SearchResultsEntity = context.createJSONUnmarshaller().unmarshalFromJSON[SearchResultsEntity](new StringReader(text), classOf[SearchResultsEntity])
 println(x)

  */

  val testFromMyMethod = JaxBConverters.JsonConverters.toJsonString(searchResultsEntity)
  println(s"textFromMyMethod is $testFromMyMethod")


  val resultOfRoundTrip: SearchResultsEntity = JaxBConverters.JsonConverters.fromJsonString[SearchResultsEntity](testFromMyMethod)
  //assert(resultOfRoundTrip == searchResultsEntity)
  println(resultOfRoundTrip)

  val realJsonString =
    """
      |{"searchResultsDTO":{"remoteProcessGroupResults":[],"funnelResults":[],"connectionResults":[],"outputPortResults":[],"inputPortResults":[],"processGroupResults":[],"processorResults":[{"id":"5e4ebc15-015e-1000-cfc7-a391470c6b0e","groupId":"5cb229a2-015e-1000-af7e-47911f0b10d6","name":"heyho","matches":["Name: heyho"]},{"id":"5e50712c-015e-1000-9fe2-4c3706633fb6","groupId":"5cb229a2-015e-1000-af7e-47911f0b10d6","name":"UpdateAttribute","matches":["Property description: Regular expression for attributes to be deleted from FlowFiles.  Existing attributes that match will be deleted regardless of whether they are updated by this processor."]}]}}
    """.stripMargin

   val realObj: SearchResultsEntity = JaxBConverters.JsonConverters.fromJsonString[SearchResultsEntity](realJsonString)

  println(s"realObj is $realObj")

  val templateText = readText("/Users/charlestassoni/Downloads/simpleTemplate4.xml")
  val templateFromXml: TemplateDTO = JaxBConverters.XmlConverters.fromXmlString[TemplateDTO](templateText)
  //templateFromXml.getSnippet
  println(templateFromXml)
  //val templateText = readText("~/Downloads/simpleGetFileTemplate.xml")

  println(
  JaxBConverters.JsonConverters.toJsonString(templateFromXml)
  )

}
