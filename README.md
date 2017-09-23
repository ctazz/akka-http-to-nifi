# Akka-HTTP interface to NIFI

[![Join the chat at https://gitter.im/theiterators/akka-http-microservice](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/theiterators/akka-http-microservice?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Work here is based on a [nice Akka HTTP example](https://github.com/theiterators/akka-http-microservice)

Sample run command:

sbt " run sampleInputs/sample1.json"
 
Note: Before running, go to the sample input file you want to use, and change any environment specific settings to something appropriate for your setup.
 
Development Note: spray.json gives is close to useless when the JSON isn't as expected. When I was expecting a Map[String, String], and the value was null, I got a message that didn't include the associated key or the JSON path so far.  (Solved the problem by expecting a Map[String, JsValue])  See https://github.com/spray/spray-json/issues/60, which is a bug registered back in 2013. It's titled "JsonFormat error messages provide too little context to be helpful", and it still hasn't been fixed. 