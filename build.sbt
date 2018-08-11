name := "sovereign"

version := "0.1"

scalaVersion := "2.12.6"

libraryDependencies += "com.amazonaws" % "aws-java-sdk" % "1.11.172"
libraryDependencies += "org.rogach" %% "scallop" % "3.1.3"


mainClass in assembly := Some("com.github.akhilrangaraj.sovereign")