// Users Entity를 DB에 저장, 조회하는 Repository

package com.my.mindot_back.users.repository;

import com.my.mindot_back.users.entity.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface UsersRepository extends JpaRepository<Users, Long> {
    // 회원가입 시 이메일 중복 검사
    // true: 이미 가입된 이메일
    boolean existsByEmail(String email);
    // 로그인시 사용, 이메일로 사용자 조회
    // Optional 사용: 이메일에 해당하는 사용자가 없을 수 있기 때문
    Optional<Users> findByEmail(String email);
}
