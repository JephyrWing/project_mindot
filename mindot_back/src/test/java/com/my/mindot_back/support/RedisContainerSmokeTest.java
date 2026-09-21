// 테스트용 Redis가 비밀번호 인증과 함께 정상적으로 실행되는지 검증

package com.my.mindot_back.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisContainerSmokeTest
        extends PostgresRedisContainerTestBase {

    @Test
    void startsRedisWithPasswordAuthentication()
            throws Exception {
        var result = REDIS.execInContainer(
                "redis-cli",
                "-a",
                TEST_REDIS_PASSWORD,
                "ping"
        );

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("PONG");
    }
}