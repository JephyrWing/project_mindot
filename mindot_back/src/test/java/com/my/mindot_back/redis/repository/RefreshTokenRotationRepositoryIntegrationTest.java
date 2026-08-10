package com.my.mindot_back.redis.repository;

import com.my.mindot_back.redis.entity.RefreshToken;
import com.my.mindot_back.common.config.RedisRepositoryConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(RefreshTokenRotationRepositoryIntegrationTest.Config.class)
@TestPropertySource(properties = {
        "spring.data.redis.host=${MINDOT_REDIS_HOST:localhost}",
        "spring.data.redis.port=${MINDOT_REDIS_PORT:6379}",
        "spring.data.redis.password=${REDIS_PW}",
        "spring.data.redis.database=15"
})
@EnabledIfEnvironmentVariable(named = "MINDOT_REDIS_TESTS", matches = "true")
class RefreshTokenRotationRepositoryIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(DataRedisAutoConfiguration.class)
    @Import({
            RefreshTokenRotationRepository.class,
            RedisRepositoryConfig.class
    })
    static class Config {
    }

    private final RefreshTokenRotationRepository repository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final StringRedisTemplate redisTemplate;
    private String key;

    @Autowired
    RefreshTokenRotationRepositoryIntegrationTest(
            RefreshTokenRotationRepository repository,
            RefreshTokenRepository refreshTokenRepository,
            StringRedisTemplate redisTemplate
    ) {
        this.repository = repository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.redisTemplate = redisTemplate;
    }

    @AfterEach
    void cleanUp() {
        if (key != null) {
            redisTemplate.delete(key);
            redisTemplate.delete(key + ":phantom");
        }
    }

    @Test
    void simultaneousRotationAllowsOnlyOneSuccessAndRevokesReuse() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        key = "auth:refresh:" + sessionId;
        Instant absoluteExpiresAt = Instant.now().plus(Duration.ofDays(30));
        redisTemplate.opsForHash().putAll(key, Map.of(
                "tokenHash", "old-hash",
                "lastUsedAt", Instant.now().toString(),
                "absoluteExpiresAt", absoluteExpiresAt.toString(),
                "ttlSeconds", "600"
        ));
        redisTemplate.expire(key, Duration.ofMinutes(10));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RefreshTokenRotationRepository.RotationOutcome> first =
                    executor.submit(() -> rotateAfter(
                            start, sessionId, "new-hash-1", absoluteExpiresAt));
            Future<RefreshTokenRotationRepository.RotationOutcome> second =
                    executor.submit(() -> rotateAfter(
                            start, sessionId, "new-hash-2", absoluteExpiresAt));

            start.countDown();

            assertThat(Set.of(first.get().result(), second.get().result()))
                    .containsExactlyInAnyOrder(
                    RefreshTokenRotationRepository.RotationResult.ROTATED,
                    RefreshTokenRotationRepository.RotationResult.REUSED
            );
        } finally {
            executor.shutdownNow();
        }

        assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    @Test
    void rotationUpdatesHashLastUsedAtAndRemainingTtlTogether() {
        String sessionId = UUID.randomUUID().toString();
        key = "auth:refresh:" + sessionId;
        Instant usedAt = Instant.now();
        Instant absoluteExpiresAt = usedAt.plusSeconds(60);
        refreshTokenRepository.save(new RefreshToken(
                sessionId,
                1L,
                "old-hash",
                UUID.randomUUID().toString(),
                Instant.EPOCH,
                absoluteExpiresAt,
                600
        ));
        assertThat(redisTemplate.hasKey(key + ":phantom")).isFalse();

        RefreshTokenRotationRepository.RotationOutcome outcome = repository.rotate(
                sessionId,
                "old-hash",
                "new-hash",
                usedAt,
                absoluteExpiresAt,
                120
        );

        assertThat(outcome.result()).isEqualTo(
                RefreshTokenRotationRepository.RotationResult.ROTATED
        );
        assertThat(outcome.expiresInSeconds()).isBetween(1L, 60L);
        assertThat(redisTemplate.opsForHash().get(key, "tokenHash"))
                .isEqualTo("new-hash");
        assertThat(redisTemplate.opsForHash().get(key, "lastUsedAt"))
                .isEqualTo(usedAt.toString());
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, 60L);

        RefreshToken updated = refreshTokenRepository.findById(sessionId)
                .orElseThrow();
        assertThat(updated.getTokenHash()).isEqualTo("new-hash");
        assertThat(updated.getLastUsedAt()).isEqualTo(usedAt);
        assertThat(updated.getAbsoluteExpiresAt()).isEqualTo(absoluteExpiresAt);
    }

    private RefreshTokenRotationRepository.RotationOutcome rotateAfter(
            CountDownLatch start,
            String sessionId,
            String newHash,
            Instant absoluteExpiresAt
    ) throws InterruptedException {
        start.await();
        return repository.rotate(
                sessionId,
                "old-hash",
                newHash,
                Instant.now(),
                absoluteExpiresAt,
                600
        );
    }
}
