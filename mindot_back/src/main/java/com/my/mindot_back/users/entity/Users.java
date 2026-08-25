package com.my.mindot_back.users.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
// protected: 다른 패키지에서 빈 사용자를 만드는 것을 막음
@NoArgsConstructor(access = AccessLevel.PROTECTED)

public class Users {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Column(nullable = false, length = 20)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 20)
    private UserRole userRole;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // 회원가입 서비스에서 호출할 생성 메서드
    // 사용자 입력 값 email, passwordHash, displayName
    private Users(String email, String passwordHash, String displayName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;

        // 기본값
        this.timezone = "Asia/Seoul";
        this.locale = "ko-KR";
        this.status = AccountStatus.ACTIVE;
        this.userRole = UserRole.ROLE_USER;
    }

    public static Users create(
            String email,
            String encodedPassword,  // 비밀번호 원문 X
            String displayName
    ) {
        return new Users(email, encodedPassword, displayName);
    }

    // DB insert 전 JPA가 자동 실행하는 메서드
    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // DB update 전 JPA가 자동 실행하는 메서드
    // 회원 정보 수정 시 updatedAt만 현재 시각으로 갱신
    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

}
