// PostgreSQL과 Redis가 필요한 통합 테스트의 공통 컨테이너 설정

package com.my.mindot_back.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class PostgresRedisContainerTestBase
        extends PostgresContainerTestBase {

    protected static final String TEST_REDIS_PASSWORD =
            "test-redis-password";

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse("redis:7.4-alpine")
            )
                    .withExposedPorts(6379)
                    .withCommand(
                            "redis-server",
                            "--appendonly",
                            "no",
                            "--requirepass",
                            TEST_REDIS_PASSWORD,
                            "--maxmemory",
                            "64mb",
                            "--maxmemory-policy",
                            "noeviction"
                    );

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerRedisProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.data.redis.host",
                REDIS::getHost
        );
        registry.add(
                "spring.data.redis.port",
                () -> REDIS.getMappedPort(6379)
        );
    }
}