// consent_events 테이블과 연결되는 Entity
package com.my.mindot_back.users.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(
        name = "consent_events",
        indexes = {
                // 사용자별 특정 동의의 최신 상태를 빠르게 조회
                @Index(
                        name = "idx_consent_events_user_type_occurred_at",
                        columnList = "user_id, consent_type, occurred_at DESC"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsentEvents {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 사용자 한명은 여러 동의 이벤트 가질 수 있음
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // 사용자가 완전히 삭제되면 해당 사용자의 동의 이력도 함께 삭제
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    // 동의 종류
    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 50, updatable = false)
    private ConsentType consentType;

    // 동의문 버전
    @Column(name = "consent_version", nullable = false, length = 30, updatable = false)
    private String consentVersion;

    // 동의 또는 철회
    // 회원가입 시 GRANTED 저장, 철회 시 REVOKED 행 새로 추가
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ConsentAction action;

    // 사용자가 상태 변경한 시각
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    // DB에 저장된 시각
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 동의 또는 철회 이벤트 생성을 위한 공통 생성자
    private ConsentEvents(
            Users user,
            ConsentType consentType,
            String consentVersion,
            ConsentAction action
    ) {
        this.user = user;
        this.consentType = consentType;
        this.consentVersion = consentVersion;
        this.action = action;
    }

    // 동의 이벤트 생성
    public static ConsentEvents grant(
            Users user,
            ConsentType consentType,
            String consentVersion
    ) {
        return new ConsentEvents(
                user,
                consentType,
                consentVersion,
                ConsentAction.GRANTED
        );
    }

    // 철회 이벤트 생성
    public static ConsentEvents revoke(
            Users user,
            ConsentType consentType,
            String consentVersion
    ) {
        return new ConsentEvents(
                user,
                consentType,
                consentVersion,
                ConsentAction.REVOKED
        );
    }

    // insert 전 동의시각과 DB 저장 시각 설정
    // PrePersist: JPA가 DB 에 insert 하기 전 자동 실행하는 메서드
    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.occurredAt = now;
        this.createdAt = now;
    }
}
