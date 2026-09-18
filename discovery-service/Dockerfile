FROM amazoncorretto:21

ARG JAR_FILE=build/libs/discovery-service-0.0.1-SNAPSHOT.jar

COPY ${JAR_FILE} discovery-service.jar

ENTRYPOINT ["java","-jar","/discovery-service.jar"]