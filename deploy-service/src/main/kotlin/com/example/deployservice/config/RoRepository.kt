package com.example.deployservice.config

import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.Repository

/**
 * 읽기 전용(replica, mysql-platform-replica) 저장소 마커. save/delete 가 없는 [Repository] 라 쓰기 메서드를 노출하지 않는다.
 * [RoJpaConfig] 가 `deploy.ro` 패키지에서 이것을 잇는 인터페이스만 빈으로 만든다.
 */
@NoRepositoryBean
interface RoRepository<T, ID> : Repository<T, ID>
