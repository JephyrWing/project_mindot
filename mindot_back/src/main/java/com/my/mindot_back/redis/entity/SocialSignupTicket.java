// 신규 소셜 회원 정보를 동의 완료 전까지 Redis에 임시 보관하는 가입 티켓
package com.my.mindot_back.redis.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

import java.time.Instant;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@RedisHash("auth:social-signup")
public class SocialSignupTicket {

    // 브라우저에 전달하는 티켓에서 Redis 데이터를 찾기 위한 식별자
    @Id
    private String ticketId;

    // Redis가 유출돼도 가입 티켓 원문을 바로 사용할 수 없도록 저장하는 해시값
    private String tokenHash;

    private String provider;
    private String providerUserId;
    private String email;
    private String displayName;
    private Instant createdAt;

    // Redis에서 티켓이 자동 삭제될 때까지 남은 시간(초)
    @TimeToLive
    private Long ttlSeconds;

    public SocialSignupTicket(
            String ticketId,
            String tokenHash,
            String provider,
            String providerUserId,
            String email,
            String displayName,
            Instant createdAt,
            long ttlSeconds
    ) {
        this.ticketId = ticketId;
        this.tokenHash = tokenHash;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
        this.displayName = displayName;
        this.createdAt = createdAt;
        this.ttlSeconds = ttlSeconds;
    }
}