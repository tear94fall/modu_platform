# syntax=docker/dockerfile:1.7
# 1단계: 소스에서 jar 를 만든다. 어디서 빌드해도 같은 이미지(CI·로컬 공통). Gradle 캐시는 BuildKit 캐시 마운트로 재사용한다.
# 캐시 마운트는 서비스별 id + sharing=locked: 여러 서비스를 동시에 빌드할 때(compose --build) 한 Gradle 홈을 컨테이너끼리 나눠 쓰면
# 프로세스 간 락 해제 신호가 다른 컨테이너에 닿지 않아 'Timeout waiting to lock journal cache' 로 1분 뒤 실패한다.
FROM amazoncorretto:21 AS build
WORKDIR /src
# amazoncorretto:21(AL2023) 에는 xargs 가 없어 gradlew 가 바로 실패한다(findutils 필요)
RUN yum -y install findutils >/dev/null && yum clean all >/dev/null
# 의존성 레이어: 빌드 스크립트만 먼저 복사해 소스가 바뀌어도 의존성 다운로드는 캐시된다
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,id=gradle-gateway-service,sharing=locked,target=/root/.gradle ./gradlew --no-daemon dependencies -q >/dev/null 2>&1 || true
COPY . .
# 테스트는 워크플로(.github/workflows/images.yml)에서 돌린다. 여기선 jar 만 만든다.
RUN --mount=type=cache,id=gradle-gateway-service,sharing=locked,target=/root/.gradle ./gradlew --no-daemon -x test bootJar \
 && cp build/libs/gateway-service-*-SNAPSHOT.jar /app.jar

# 2단계: 실행. exec 형식이라 PID 1 이 java(SIGTERM → graceful shutdown). MaxRAMPercentage 로 컨테이너 메모리 제한을 따른다.
FROM amazoncorretto:21
COPY --from=build /app.jar /gateway-service.jar
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/gateway-service.jar"]
