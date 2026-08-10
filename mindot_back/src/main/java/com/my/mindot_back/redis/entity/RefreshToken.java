package com.my.mindot_back.redis.entity;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;
import lombok.Getter;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@RedisHash("auth:refresh")
public class RefreshToken {

    @Id
    private String sessionId;

    private Long userId;

    // Refresh Token 원문이 아니라 SHA-256 결과
    private String tokenHash;

    // Refresh Token Rotation 계열 식별자
    private String tokenFamilyId;

    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant absoluteExpiresAt;

    // Redis 키의 남은 유효시간(초)
    @TimeToLive
    private Long ttlSeconds;

    public RefreshToken(
            String sessionId,
            Long userId,
            String tokenHash,
            String tokenFamilyId,
            Instant createdAt,
            Instant absoluteExpiresAt,
            long ttlSeconds
    ) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.tokenFamilyId = tokenFamilyId;
        this.createdAt = createdAt;
        this.lastUsedAt = createdAt;
        this.absoluteExpiresAt = absoluteExpiresAt;
        this.ttlSeconds = ttlSeconds;
    }

    public void rotate(
            String newTokenHash,
            Instant usedAt,
            long newTtlSeconds
    ) {
        this.tokenHash = newTokenHash;
        this.lastUsedAt = usedAt;
        this.ttlSeconds = newTtlSeconds;
    }

    public boolean isAbsolutelyExpired(Instant now) {
        return !now.isBefore(absoluteExpiresAt);
    }
}
