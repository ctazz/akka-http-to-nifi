# Akka-HTTP interface to NIFI

[![Join the chat at https://gitter.im/theiterators/akka-http-microservice](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/theiterators/akka-http-microservice?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Work here is based on a [nice Akka HTTP example](https://github.com/theiterators/akka-http-microservice)

Sample run commands:

sbt " run sampleInputs/for-file-to-kafka-template.json"
 
sbt " run sampleInputs/for-http-response-depends-on-kafka.json"

Note: Before running, go to the sample input file you want to use, and change any environment specific settings to something appropriate for your setup. 