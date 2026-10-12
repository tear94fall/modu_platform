import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    // JPA 엔티티(DeploymentEntity)의 기본 생성자·open 클래스
    kotlin("plugin.jpa") version "2.4.21"
    id("org.springframework.boot") version "4.0.8"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springCloudVersion"] = "2025.1.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // 배포 이력 저장(mysql-platform 의 modu-platform.deployment). 스키마는 DBA/인프라(modu_infra data/mysql/schema)가 만들고 앱은 validate 만 한다.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.mysql:mysql-connector-j")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // API 문서(GET /v3/api-docs, JSON 만). Boot 4.0 ↔ springdoc 3.0.x
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    // 롤아웃 감시(Deployment·ReplicaSet·Pod). 파드 안에선 ServiceAccount, 로컬에선 ~/.kube/config 로 붙는다.
    implementation("io.fabric8:kubernetes-client:7.9.0")
    // 로컬 kubeconfig(colima/k3s)의 EC 클라이언트 키를 fabric8 이 읽으려면 필요하다. 파드 안(ServiceAccount 토큰)에선 쓰이지 않는다.
    runtimeOnly("org.bouncycastle:bcpkix-jdk18on:1.86")
    // 로그를 한 줄 JSON 으로(logback-spring.xml 의 LogstashEncoder + StructuredArguments)
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Boot 4 에서 테스트 지원이 서버 종류별 스타터로 갈라졌다.

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // @DataJpaTest·@AutoConfigureTestDatabase 도 Boot 4 에서 별도 스타터로 갈라졌다.
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    // 테스트 DB(H2, MODE=MySQL) — src/test/resources/config/application.yml
    testRuntimeOnly("com.h2database:h2")
}

// Spring Cloud BOM(2024.0.0)이 fabric8 api/model 을 6.13.4 로 끌어내려 client(7.9.0)와 어긋난다(기동 때 NoClassDefFoundError
// V1DynamicresourceAllocationAPIGroupDSL). BOM 보다 우선하도록 resolutionStrategy 로 io.fabric8 전부를 한 버전에 맞춘다.
val fabric8Version = "7.9.0"
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.fabric8") useVersion(fabric8Version)
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

// JPA 엔티티는 Hibernate 프록시 생성을 위해 open 이어야 한다(modu_chat 서비스와 같은 규칙).
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
