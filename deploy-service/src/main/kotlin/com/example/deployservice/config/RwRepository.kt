package com.example.deployservice.config

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.NoRepositoryBean

/** 읽기/쓰기(master, mysql-platform) 저장소 마커. [RwJpaConfig] 가 `deploy.rw` 패키지에서 이것을 잇는 인터페이스만 빈으로 만든다. */
@NoRepositoryBean
interface RwRepository<T, ID> : JpaRepository<T, ID>
