
import java.io.{StringReader, ByteArrayOutputStream}
import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBException
import javax.xml.bind.Marshaller
import javax.xml.bind.Unmarshaller


import org.apache.nifi.web.api.dto.flow.FlowDTO
import org.apache.nifi.web.api.dto.status.ProcessorStatusDTO

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


  import scala.collection.JavaConverters._

  def fromFlowDTO(flowDTO: FlowDTO): Set[Option[String]] = {
    flowDTO.getProcessors.asScala.map{proc:ProcessorEntity =>
      val status:ProcessorStatusDTO = proc.getStatus
      val processorDTO: ProcessorDTO = proc.getComponent
      processorDTO
    }.map{processorDTO: ProcessorDTO =>
      val validationErrors: Iterable[String] = processorDTO.getValidationErrors.asScala
      val state = processorDTO.getState
      println(s"state is $state and validationErrors are $validationErrors")
      processorDTO.getConfig
    }.map{processorConfigDTO: ProcessorConfigDTO =>
      //key we want is "HTTP Context Map"
      //!!!Problem when processing notes/jsonDumps/responseUponImportingTemplateIntoAProcessGroup.json is
      //that, although properties appear when we parse with https://jsonformatter.curiousconcept.com/, the properties
      //Map here is empty, as are any complex object. Simple attributes of ProcessorConfigDTO, such as schedulingPeriod, are
      //deserialized correctly.
      println(s"properties are ${processorConfigDTO.getProperties}")
      println(s"descriptors are ${processorConfigDTO.getDescriptors}")
      processorConfigDTO.getProperties.asScala.get("HTTP Context Map")
    }.to[Set]
  }

  def distinctPropertiesForPropertyName(flowDTO: FlowDTO, propertyName: String): Set[String] = {
    flowDTO.getProcessors.asScala.map(_.getComponent.getConfig.getProperties.asScala.get("propertyName")).to[Set].collect{case Some(x) => x}
  }



  //@@@@@Finding Http Context Maps inside JSON responses
  //Unfortunately, the processors / component / config / properties Map in each of these shows up as a 0 element Map, and
  //until that bug is fixed, we can't use the JaxB representations to find Http Context Maps.
  //Http Context Map ids from can also be found in .... / config /descriptors, which is another Map, and in there
  //the key is "HTTP Context Map", and that should somehow hold allowableValues / allowableValue / value, and the associated
  //entry will be the Http Context Map id. But descriptors also unmarshalls as an empty Map.


  //1)This looks for Http Context Map ids in the json response for a GET /flow/process-groups/{id}
  val pgfe: ProcessGroupFlowEntity =
    JaxBConverters.JsonConverters.fromJsonString[ProcessGroupFlowEntity](
      Misc.readText("notes/jsonDumps/fromFlowSlashprocess-groupsSlashId2.json")
    )
  pgfe.getProcessGroupFlow.getFlow.getProcessGroups.asScala

/*  pgfe.getProcessGroupFlow.getFlow.getConnections.asScala.map{conn =>
    ???
  }*/



  val httpContextMapIds: Set[Option[String]] = fromFlowDTO(pgfe.getProcessGroupFlow.getFlow)

  println("ids of HTTP Context Maps: " + httpContextMapIds)

  //2) This looks for Http Context Map ids in the json response from a
  //POST process-groups/${processGroupId}/template-instance (The purpose of such a post is to import a template into a process group)
  //The difference between this JSON and that from GET /flow/process-groups/{id} is that this JSON doesn't have the enclosing
  //processGroupFlow / flow layers, and also doesn't show permissions at the outer layer.
  val flowEntity = JaxBConverters.JsonConverters.fromJsonString[FlowEntity]  (Misc.readText("notes/jsonDumps/another.json"))
  val anotherTry = fromFlowDTO(flowEntity.getFlow)
  println(s"another try gives $anotherTry)")


}
