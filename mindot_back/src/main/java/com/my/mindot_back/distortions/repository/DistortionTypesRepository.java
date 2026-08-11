// distortion_types 테이블의 조회, 저장을 담당하는 repository
package com.my.mindot_back.distortions.repository;

import com.my.mindot_back.distortions.entity.DistortionTypes;
import org.springframework.data.jpa.repository.JpaRepository;

// JpaRepo 상속 -> 기본적인 저장, 조회, 삭제 기능을 Spring Data JPA가 자동으로 만듦
public interface DistortionTypesRepository
        extends JpaRepository<DistortionTypes, Long> {
    // 같은 code가 이미 DB에 있는지 확인
    boolean existsByCode(String code);
}
