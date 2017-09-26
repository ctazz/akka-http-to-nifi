# Akka-HTTP interface to NIFI

Work here is based on a [nice Akka HTTP example](https://github.com/theiterators/akka-http-microservice)

We have sample input data you can run to get an idea of how this app works.

To run the sample you should have a working NIFI instance. You'll probably also want to have a Kafka cluster available, so that you can test that the templates have been correctly instantiated into processor groups. It's fine if these are local instances. 

##Usage

You can run this app as either an Akka-http server or from the command line.

Note: Before running the sample, go to sampleInputs/sample1.json and change any environment specific settings to something appropriate for your setup.

###Running as an http service

In build.sbt, make sure that you have this line uncommented:

```Keys.mainClass in (Compile) := Some("NifiServiceImpl")```

and this line commented out:

```Keys.mainClass in (Compile) := Some("TheScript")```

If you're switching from running in one mode (e.g., as an http service with NifiServiceImpl) to running in another mode (e.g. from the command line with TheScript), make sure you do an ```sbt clean test``` between the two runs. 

Start the service in development mode by first typing ```sbt``` at the command line, and then, at the sbt prompt, typing

```~re-start```

(By the way, that command is one of the advantages of using sbt over maven. That will restart the server every time you modify code in the project. ```~test``` is another useful sbt command.)

Now, if you want to run the sample, open up a different terminal tab, but in the same directory, and run this cURL:

```curl --include --request POST  -H 'Content-type: application/json'  --data @sampleInputs/sample1.json  -X POST http://localhost:9000/processor-groups
```

You should get a response of 201. At that point you can check whether the templates you customized with sampleInputs1.json have been instantiated as working NIFI processor groups. (See below.)

###Running as a command line service

Make sure that you have this line uncommented in build.sbt:

```Keys.mainClass in (Compile) := Some("TheScript")```

If you want to run the sample, type in this command: 

```sbt " run sampleInputs/sample1.json"```
 

##How it works
 
The sampleInputs/sample1.json is an array, each element of which specifies: 
 
* The name of the template file to use. 

	* The template files used by sample1.json are in the templatePrototypes directory.

	* The application figures out where to look for template files based on the template.directory in src/main/resources/application.conf. The template directory need not be inside the application's directory structure. 
	
* The name to give to the process group that will be instantiated from the template.

* A set of key-value pairs that will be used to override tokens in the specified template file. 

For example, the first element in the json array uses the http-response-depends-on-kafka template file in the templatePrototypes directory. (The application automatically adds the ".xml" suffix.) That file contains this token: "{{knownBrokers}}". The application will take the value of the templateReplacementValues/knownBrokers attribute in the first array element and use it in place of the {{knownBrokers}}" token in the http-response-depends-on-kafka file.
 
The app will then do the following, all by using the NIFI REST api:

* upload the customized text from the chosen template file into the NIFI instance

* create a processor group with the name that's associated with the templateFileName key in the sample JSON.
   
* import the template into the processor group, thus instantiating whatever processors were configured in the template file   
   
* find any Http Context Maps that the processors depend on, and enable them
   
* find any processors that are not running and cause them to run   

##How to verify the newly created processor groups

Go to your NIFL GUI, which is perhaps your [local NIFI GUI](http://localhost:8081/nifi/), and check that the expected processor groups exist and that the templates have been uploaded. Note that currently processor groups are all given the same initial position, so you'll need to move the top processor group with your mouse to see the next processor group.

###Verifying http-response-depends-on-kafka's processor group

Inside your Kafka directory, run this command (assuming you're running a local Kafka instance)':

```bin/kafka-console-consumer.sh --zookeeper localhost:<zookeeper-port> --topic <yourTopic>```

At any terminal, run this command, which assumes you haven't changed either the listeningPort, the allowedPaths, or the topicNameRule in sample1.json, and that you're running a local NIFI instance: 

```curl --include --request POST -H Topic:<yourTopic> -H 'Content-type: application/json'  --data '[{"key":"val", "keyxx":"valuex"}, {"keykey":"v", "keydddd":"dddd"}]'  -X POST http://localhost:9010/data```

If the processor group is working, you'll see the POST data appear in the Kafka console consumer.

###Verifying file-to-kafka.xml's processor group

Keep the Kafka console consumer from the http processor group verification open.

Create a file with some content; that is, don't make the file empty.

Assuming you didn't change the fileFilter or fileToKafkaTopicRule in sample1.json, run

```cp <yourFile> <pathToInputDirectoryYouChose>/<yourTopic>_anythingAtAll.anySuffix```

You should see the text in your file appear in the Kafka console consumer.

##Todo
 
Tear down NFII processor groups. 
 
Either eliminate usage of NIFI JAXB representations or use it more. I wasn't able to get anything other than an empty Map in the flow/processors/component/config/properties key when using JAXB to deserialize the NIFI response to a /nifi-api/process-groups/${processGroupId}/template-instance POST.  
  
General tightening and clean up

##Development Notes

Most of the documentation I found online about working with the NIFI REST api were old. But this reference was useful: https://nifi.apache.org/docs/nifi-docs/rest-api/index.html  For me, the best way to figure things out was to click around in the NIFI GUI while using the Chrome developer tools to see what HTTP calls were issued and what responses were received. Looking at the org.apache.nifi.web.api.dto package in the "org.apache.nifi" % "nifi-client-dto" % "1.3.0" project was also useful on occasion.
 
spray.json gives is close to useless when the JSON isn't as expected. When I was expecting a Map[String, String], and the value was null, I got a message that didn't include the associated key or the JSON path so far.  (Solved the problem by expecting a Map[String, JsValue])  See https://github.com/spray/spray-json/issues/60, which is a bug registered back in 2013. It's titled "JsonFormat error messages provide too little context to be helpful", and it still hasn't been fixed.
 
I've used sbt rather than maven here because that's what the Akka HTTP example had, and because it lets me develop more quickly. Developers who enhance this version may well want to switch to maven. 