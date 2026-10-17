import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    // JPA 엔티티(batch_job·batch_job_run·batch_job_change)의 기본 생성자·open 클래스
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
    // 잡 정의·실행 이력(mysql-platform 의 modu-platform.batch_job*). 스키마는 DBA/인프라(modu_infra data/mysql/schema)가 만들고 앱은 validate 만 한다.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.mysql:mysql-connector-j")
    // 파드가 2개 이상이어도 같은 잡의 cron 회차가 한 번만 돌게 하는 DB 락(shedlock 테이블, master 에만 — SchedulerLockConfig)
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // API 문서(GET /v3/api-docs, JSON 만). Boot 4.0 ↔ springdoc 3.0.x
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
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

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

// JPA 엔티티는 Hibernate 프록시 생성을 위해 open 이어야 한다(deploy-service·modu_chat 서비스와 같은 규칙).
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
