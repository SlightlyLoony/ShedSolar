#! /bin/bash

cd /apps/shedsolar
java -jar -agentlib:jdwp=transport=dt_socket,server=n,address=beauty.dilatush.com:5005,suspend=y ShedSolar.jar
