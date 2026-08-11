/*
 * Refresh Token 교체를 Redis Lua Script로 한 번에 처리
 *
 * 여러 refresh 요청이 동시에 들어와도
 * “기존 토큰 검증 → 새 해시 저장 → TTL 갱신”이 분리되지 않으므로,
 * 이전 Refresh Token의 재사용을 안전하게 감지 가능
 */
package com.my.mindot_back.redis.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRotationRepository {

    private static final String KEY_PREFIX = "auth:refresh:";

    private static final DefaultRedisScript<Long> ROTATE_SCRIPT =
            new DefaultRedisScript<>("""
                    local currentHash = redis.call('HGET', KEYS[1], 'tokenHash')
                    if not currentHash then
                        return 0
                    end
                    local absoluteExpiresAt = redis.call(
                        'HGET', KEYS[1], 'absoluteExpiresAt')
                    if not absoluteExpiresAt or absoluteExpiresAt ~= ARGV[4] then
                        redis.call('DEL', KEYS[1])
                        return -3
                    end
                    local redisTime = redis.call('TIME')
                    local absoluteRemainingSeconds =
                        tonumber(ARGV[5]) - tonumber(redisTime[1])
                    if absoluteRemainingSeconds <= 0 then
                        redis.call('DEL', KEYS[1])
                        return -1
                    end
                    if currentHash ~= ARGV[1] then
                        redis.call('DEL', KEYS[1])
                        return -2
                    end
                    local ttlSeconds = math.min(
                        tonumber(ARGV[6]), absoluteRemainingSeconds)
                    redis.call('HSET', KEYS[1],
                        'tokenHash', ARGV[2],
                        'lastUsedAt', ARGV[3],
                        'ttlSeconds', tostring(ttlSeconds))
                    redis.call('EXPIRE', KEYS[1], ttlSeconds)
                    return ttlSeconds
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RotationOutcome rotate(
            String sessionId,
            String receivedHash,
            String newTokenHash,
            Instant usedAt,
            Instant absoluteExpiresAt,
            long idleExpirationSeconds
    ) {
        Long result = redisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(KEY_PREFIX + sessionId),
                receivedHash,
                newTokenHash,
                usedAt.toString(),
                absoluteExpiresAt.toString(),
                Long.toString(absoluteExpiresAt.getEpochSecond()),
                Long.toString(idleExpirationSeconds)
        );

        return RotationOutcome.from(result);
    }

    public enum RotationResult {
        INVALID,
        ROTATED,
        EXPIRED,
        REUSED;
    }

    public record RotationOutcome(
            RotationResult result,
            long expiresInSeconds
    ) {
        private static RotationOutcome from(Long value) {
            if (value == null || value == 0 || value < -3) {
                return new RotationOutcome(RotationResult.INVALID, 0);
            }

            if (value > 0) {
                return new RotationOutcome(RotationResult.ROTATED, value);
            }

            return switch (value.intValue()) {
                case -1 -> new RotationOutcome(RotationResult.EXPIRED, 0);
                case -2 -> new RotationOutcome(RotationResult.REUSED, 0);
                default -> new RotationOutcome(RotationResult.INVALID, 0);
            };
        }
    }
}
