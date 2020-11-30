#! /bin/bash

cd /apps/shedsolar
java -jar -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5005 ShedSolar.jar
