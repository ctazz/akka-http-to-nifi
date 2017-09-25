import spray.json.{JsValue, DefaultJsonProtocol}

//An alternative would have been to use the Nifi JaxB representations, and indeed, we have in places.
// But, at least in the current version, those representations don't always pull in everything we need.
//For example, processors/component/config/properties Map is empty when we read it, and we need that Map.
//Our models are not full representations of the NIFI JSON. Instead we represent only what we need.
object NifiApiModel {

  case class Revision(version: Int, clientId: Option[String])
  //Connection has a component, but for now we don't care about it.
  case class Connection(id: String, revision: Revision)
  //Would like to make Properties a Map[String, String], but NIFI allows nulls here, and spray.json won't handle that.
  case class ProcessorConfig(properties: Map[String, JsValue])
  //ProcessorComponent has an id, but since it's the same one that's one Processor, we don't care about it.
  case class ProcessorComponent(name: String, state: String, config: ProcessorConfig)
  case class Processor(id: String, component: ProcessorComponent, revision: Revision)
  case class NifiFlow(processors: Vector[Processor], connections: Vector[Connection])

  
  


}

case class InputData(templateFileName: String, templateReplacementValues: Map[String, String], processorGroupName: String)


case class UpdateComponentInfo(id: String, state: String)
case class UpdateInfo(revision: NifiApiModel.Revision, component: UpdateComponentInfo)

trait Protocol extends DefaultJsonProtocol {
  import NifiApiModel._
  implicit val revisionFmt = jsonFormat2(Revision.apply)
  implicit val connectionFormat = jsonFormat2(Connection.apply)
  implicit val processorConfigFmt = jsonFormat1(ProcessorConfig.apply)
  implicit val processorComponentFmt = jsonFormat3(ProcessorComponent.apply)
  implicit val processorFormat = jsonFormat3(Processor.apply)
  implicit val flowFmt = jsonFormat2(NifiFlow.apply)


  implicit val updateComponentInfoFmt = jsonFormat2(UpdateComponentInfo.apply)
  implicit val updateInfoFmt = jsonFormat2(UpdateInfo.apply)


  //This data is not used to interact with Nifi. Instead it's used to tell our service what changes our client wants to effect in Nifi.
  implicit val inputDataFormat = jsonFormat3(InputData.apply)
}