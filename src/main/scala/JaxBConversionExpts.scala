
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


/*  //TODO Maybe we can re-use one context (loaded up with all the entity types we plan on handling) andone marshaller for xml and one for json
  //Should be able to end up with less copy-paste
  object JaxBConverters {

    object XmlConverters {
      def toXmlString[T](jaxBObj: T)(implicit CT: ClassTag[T]): String = {

        val context = JAXBContext.newInstance(CT.runtimeClass)
        val baos: ByteArrayOutputStream = new ByteArrayOutputStream
        context.createMarshaller().marshal(jaxBObj, baos)
        try {
          baos.toString("UTF-8")
        } finally {
          baos.close()
        }
      }

      def fromXmlString[T](str: String)(implicit CT: ClassTag[T]): T = {
        val context = JAXBContext.newInstance(CT.runtimeClass)
        val stringReader = new StringReader(str)
        try {
          //TODO Perhaps we should make our return a Try[T]. unmarshall only gives us an Object return
          //unless we give it a some Java-ish xml argument. But in that case we should probably make the analogous fromXmlString
          //a Try too.
          context.createUnmarshaller().unmarshal(stringReader).asInstanceOf[T]   //(stringReader, CT.runtimeClass).asInstanceOf[T]
        } finally {
          stringReader.close
        }

      }


    }

    object JsonConverters {
      import com.sun.jersey.api.json._


      //Got example of how to use JSONConfiguration and JSONJAXBContext from http://krasserm.blogspot.com/2012/02/using-jaxb-for-xml-and-json-apis-in.html
      //rootUnwrapping was originally false.  I think this variable tells whether we expect an outer wrapper, and, at least for the SearchResultsEntity that
      //I've received so far from my local NIFI server, NFI results don't come with an outer wrapper.
      lazy val jsonConfiguration = JSONConfiguration.natural().rootUnwrapping(true).build()
      //TODO maybe just have one jsonContext like this, assuming JSONJAXBContext is thread safe:
      //val context = new JSONJAXBContext(config, classOf[SearchResultsEntity], classOf[TemplateEntity])

      def toJsonString[T](jaxBObj: T)(implicit CT: ClassTag[T]): String = {
        val context = new JSONJAXBContext(jsonConfiguration, CT.runtimeClass)
        val baos: ByteArrayOutputStream = new ByteArrayOutputStream
        context.createJSONMarshaller().marshallToJSON(searchResultsEntity, baos)
        try {
          baos.toString("UTF-8")
        } finally {
          baos.close()
        }
      }

      //import scala.reflect.ClassTag
      //import scala.reflect._
      def fromJsonString[T](str: String)(implicit CT: ClassTag[T]): T = {
        val context = new JSONJAXBContext(jsonConfiguration, CT.runtimeClass)
        val stringReader = new StringReader(str)
        try {
          //Note: classTag[T].runtimeClass also works here, so long as we import scala.reflect._
          context.createJSONUnmarshaller().unmarshalFromJSON(stringReader, CT.runtimeClass).asInstanceOf[T]
        } finally {
          stringReader.close
        }

      }

    }


  }*/

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

  val templateText = readText("/Users/charlestassoni/Downloads/simpleTemplate3.xml")
  val templateFromXml: TemplateDTO = JaxBConverters.XmlConverters.fromXmlString[TemplateDTO](templateText)
  //templateFromXml.getSnippet
  println(templateFromXml)
  //val templateText = readText("~/Downloads/simpleGetFileTemplate.xml")

  println(
  JaxBConverters.JsonConverters.toJsonString(templateFromXml)
  )

}
