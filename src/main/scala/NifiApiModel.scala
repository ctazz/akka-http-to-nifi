import spray.json.{JsValue, DefaultJsonProtocol}

//An alternative would have been to use the Nifi JaxB representations, and indeed, we have in places.
// But, at least in the current version, those representations don't always pull in everything we need.
//For example, processors/component/config/properties Map is empty when we read it, and we need that Map.
//Our models are not full representations of the NIF JSON. Instead we represent only what we need.
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

case class InputData(templateFileName: String, templateReplacementValues: Map[String, String], nifiRootProcessorGroupId: String, processorGroupName: String)


//TODO The revision we need to send has clientId and versionId in the opposite directtion of the revision we receive.
//BUT MAYTBE THE ORDER DOESN't matter. I would hope not.
//MAYBE I NEED TO MAKE THE VERSION optional!
//Or maybe we just haven't received anything with revisions.
//s"""{"revision":{"clientId":"$clientId","version":0},"component":{"id":"$componentId","state":"$desiredState"}}"""
case class UpdateComponentInfo(id: String, state: String)
case class UpdateInfo(revision: NifiApiModel.Revision, component: UpdateComponentInfo)
//And we need an update component. Maybe it's just: {"id":"$componentId","state":"$desiredState"}
// And for processGroup creation (which is not an update) we've seen it be this: {"name":"$name","position":{"x":181,"y":140.5}
//But maybe we just leave processGroup creation as a String substitution.
//case class MaybeThisUpdate(revision: Revision)

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
}