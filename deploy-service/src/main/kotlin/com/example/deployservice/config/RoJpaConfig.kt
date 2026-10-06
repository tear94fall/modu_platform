package com.example.deployservice.config

import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * 배포 이력 DB 의 읽기 전용 쪽(replica = mysql-platform-replica, 계정 platform_ro, `spring.datasource.replica.*`).
 * 이력 목록·서비스 표의 최근 배포처럼 몇 초 늦어도 되는 조회만 여기로 온다(라우팅 규칙은 JpaDeploymentStore KDoc).
 * 트랜잭션은 `@Transactional(transactionManager = "roTransactionManager", readOnly = true)` 로 명시해서 쓴다.
 */
@Configuration
@EnableJpaRepositories(
    includeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [RoRepository::class])],
    basePackages = ["com.example.deployservice.deploy.ro"],
    entityManagerFactoryRef = "roEntityManagerFactory",
    transactionManagerRef = "roTransactionManager",
)
class RoJpaConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.replica")
    fun roDataSourceProperties(): DataSourceProperties = DataSourceProperties()

    /** `spring.datasource.replica.hikari.*` 가 풀 설정에 바인딩되는 실제 Hikari 풀. */
    @Bean(name = ["roHikariDataSource"])
    @ConfigurationProperties("spring.datasource.replica.hikari")
    fun roHikariDataSource(@Qualifier("roDataSourceProperties") props: DataSourceProperties): HikariDataSource =
        props.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()

    @Bean(name = ["roDataSource"])
    fun roDataSource(@Qualifier("roHikariDataSource") hikari: HikariDataSource): DataSource = LazyConnectionDataSourceProxy(hikari)

    /** 레플리카는 읽기 전용이라 스키마 DDL/검증(hbm2ddl)을 하지 않는다. 스키마는 master 에서 복제로 온다. */
    @Bean(name = ["roEntityManagerFactory"])
    fun roEntityManagerFactory(
        @Qualifier("roDataSource") dataSource: DataSource,
        builder: EntityManagerFactoryBuilder,
    ): LocalContainerEntityManagerFactoryBean =
        builder.dataSource(dataSource)
            .packages(RwJpaConfig.ENTITY_PACKAGE)
            .persistenceUnit("RO")
            .properties(mapOf("hibernate.hbm2ddl.auto" to "none"))
            .build()

    @Bean(name = ["roTransactionManager"])
    fun roTransactionManager(@Qualifier("roEntityManagerFactory") factory: EntityManagerFactory): PlatformTransactionManager =
        JpaTransactionManager(factory)
}
