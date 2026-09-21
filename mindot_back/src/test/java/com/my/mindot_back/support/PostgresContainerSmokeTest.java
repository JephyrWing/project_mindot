// 임시 PostgreSQL과 pgvector 확장이 정상적으로 준비되는지 검증

package com.my.mindot_back.support;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresContainerSmokeTest
        extends PostgresContainerTestBase {

    @Test
    void startsDisposablePostgresWithVectorExtension()
            throws Exception {
        try (
                var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                );
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT current_database(),
                               EXISTS (
                                   SELECT 1
                                   FROM pg_extension
                                   WHERE extname = 'vector'
                               )
                        """)
        ) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1))
                    .isEqualTo("mindot_test");
            assertThat(result.getBoolean(2))
                    .isTrue();
        }
    }
}