// Users Entity를 DB에 저장, 조회하는 Repository

package com.my.mindot_back.users.repository;

import com.my.mindot_back.users.entity.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface UsersRepository extends JpaRepository<Users, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from Users u where u.id = :id")
    Optional<Users> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);
    // 회원가입 시 이메일 중복 검사
    // true: 이미 가입된 이메일
    boolean existsByEmail(String email);

    // 로그인시 사용, 이메일로 사용자 조회
    // Optional 사용: 이메일에 해당하는 사용자가 없을 수 있기 때문
    Optional<Users> findByEmail(String email);

    // 카카오 회원번호로 이미 연결된 회원 조회
    Optional<Users> findByKakaoAccountLink(String kakaoAccountLink);

    // 구글 sub로 이미 연결된 회원 조회
    Optional<Users> findByGoogleAccountLink(String googleAccountLink);
}
