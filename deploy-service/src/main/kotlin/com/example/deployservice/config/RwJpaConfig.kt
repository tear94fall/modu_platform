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
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * 배포 이력 DB 의 읽기/쓰기 쪽(master = mysql-platform, `spring.datasource.master.*`). 모든 쓰기와 진행률 조회가 여기로 간다.
 * `spring.jpa.hibernate.ddl-auto`(운영 validate, 테스트 update)는 이 영속성 단위에만 적용된다 — RO 는 [RoJpaConfig] 에서 none.
 * `@Transactional` 기본(이름 없는) 트랜잭션 매니저가 이것(@Primary)이다.
 */
@Configuration
@EnableJpaRepositories(
    includeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [RwRepository::class])],
    basePackages = ["com.example.deployservice.deploy.rw"],
    entityManagerFactoryRef = "rwEntityManagerFactory",
    transactionManagerRef = "rwTransactionManager",
)
class RwJpaConfig {

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource.master")
    fun rwDataSourceProperties(): DataSourceProperties = DataSourceProperties()

    /** `spring.datasource.master.hikari.*` 가 풀 설정에 바인딩되는 실제 Hikari 풀. readiness 의 masterDb 도 이 풀을 본다. */
    @Bean(name = ["rwHikariDataSource"])
    @ConfigurationProperties("spring.datasource.master.hikari")
    fun rwHikariDataSource(@Qualifier("rwDataSourceProperties") props: DataSourceProperties): HikariDataSource =
        props.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()

    @Primary
    @Bean(name = ["rwDataSource", "dataSource"])
    fun rwDataSource(@Qualifier("rwHikariDataSource") hikari: HikariDataSource): DataSource = LazyConnectionDataSourceProxy(hikari)

    @Primary
    @Bean(name = ["rwEntityManagerFactory"])
    fun rwEntityManagerFactory(
        @Qualifier("rwDataSource") dataSource: DataSource,
        builder: EntityManagerFactoryBuilder,
    ): LocalContainerEntityManagerFactoryBean =
        builder.dataSource(dataSource).packages(ENTITY_PACKAGE).persistenceUnit("RW").build()

    @Primary
    @Bean(name = ["rwTransactionManager", "transactionManager"])
    fun rwTransactionManager(@Qualifier("rwEntityManagerFactory") factory: EntityManagerFactory): PlatformTransactionManager =
        JpaTransactionManager(factory)

    companion object {
        /** DeploymentEntity 가 있는 패키지(RW·RO 가 같은 엔티티를 쓴다). */
        const val ENTITY_PACKAGE = "com.example.deployservice.deploy"
    }
}
