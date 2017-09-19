# Akka-HTTP interface to NIFI

[![Join the chat at https://gitter.im/theiterators/akka-http-microservice](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/theiterators/akka-http-microservice?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Work here is based on a [nice Akka HTTP example](https://github.com/theiterators/akka-http-microservice)

Sample run command:

sbt " run 5cb229a2-015e-1000-af7e-47911f0b10d6  src/main/resources/nifi-templates/http-response-depends-on-kafka.xml"

Note: For now you should use http-response-depends-on-kafka.xml as the template file, because I haven't figured out how to pass our JSON template-customization String in through sbt. 
