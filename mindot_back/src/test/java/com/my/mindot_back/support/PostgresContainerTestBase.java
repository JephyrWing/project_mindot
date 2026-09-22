// PostgreSQL 통합 테스트에서 공통으로 사용할 pgvector 컨테이너 설정

package com.my.mindot_back.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgresContainerTestBase {

    protected static final String TEST_JWT_SECRET =
            "bWluZG90LXRlc3Qtand0LXNlY3JldC1rZXktMzItYnl0ZXMtbG9uZw==";

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg16")
                    .withDatabaseName("mindot_test")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withInitScript("test-db-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerTestProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                POSTGRES::getJdbcUrl
        );
        registry.add(
                "spring.datasource.username",
                POSTGRES::getUsername
        );
        registry.add(
                "spring.datasource.password",
                POSTGRES::getPassword
        );

        // 여러 Spring 테스트 Context가 동시에 유지돼도 DB 연결 한도를 넘지 않게 제한
        registry.add(
                "spring.datasource.hikari.maximum-pool-size",
                () -> "3"
        );
        registry.add(
                "spring.datasource.hikari.minimum-idle",
                () -> "0"
        );

        registry.add(
                "spring.jpa.hibernate.ddl-auto",
                () -> "create-drop"
        );
        registry.add(
                "mindot.demo-seed.enabled",
                () -> "false"
        );

        registry.add(
                "spring.ai.openai.api-key",
                () -> "test-openai-key"
        );
        registry.add(
                "auth.jwt.secret",
                () -> TEST_JWT_SECRET
        );
        registry.add(
                "spring.data.redis.password",
                () -> "test-redis-password"
        );
    }
}