FROM amazoncorretto:21

ARG JAR_FILE=build/libs/discovery-service-0.0.1-SNAPSHOT.jar

COPY ${JAR_FILE} discovery-service.jar

# exec 형식: PID 1 이 java 라 SIGTERM 을 직접 받는다(graceful shutdown). MaxRAMPercentage 로 컨테이너 메모리 제한을 따른다.
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/discovery-service.jar"]
