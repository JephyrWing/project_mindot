package com.my.mindot_back.seed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Must point only at a newly created disposable test DB, never the application DB. */
@EnabledIfEnvironmentVariable(named = "MINDOT_SEED_TEST_URL", matches = ".+")
class DemoSeedInitializerIntegrationTest {
    @Test
    void actualJdbcStartupIsAtomicIdempotentAndPreservesExistingData() throws Exception {
        String url = System.getenv("MINDOT_SEED_TEST_URL");
        assertThat(url).startsWith("jdbc:postgresql://127.0.0.1:")
                .endsWith("/mindot_seed_startup_test");
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "postgres", "seed-test-only");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // No DROP/reset: fail if this disposable DB has already been used.
        try (var input = new ClassPathResource("seed/local-schema-fixture.sql").getInputStream()) {
            jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        DemoSeedInitializer initializer = new DemoSeedInitializer(ds);
        // Fresh local DB can start so teammates can register user 1 normally.
        initializer.run(null);
        assertThat(count(jdbc, "emotion_records")).isZero();

        jdbc.execute("""
                INSERT INTO public.users(id,created_at,display_name,email,locale,status,timezone,updated_at,user_role)
                VALUES (1,now(),'Seed fixture','seed1@example.invalid','ko','ACTIVE','UTC',now(),'ROLE_USER'),
                       (2,now(),'Other fixture','seed2@example.invalid','ko','ACTIVE','Asia/Seoul',now(),'ROLE_USER');
                """);
        assertThat(assertThrows(SQLException.class, () -> initializer.run(null)).getMessage())
                .contains("timezone differs");
        jdbc.update("UPDATE public.users SET timezone='Asia/Seoul' WHERE id=1");
        assertThat(assertThrows(SQLException.class, () -> initializer.run(null)).getMessage())
                .contains("Missing or duplicate distortion code");
        String[] codes = {"ALL_OR_NOTHING_THINKING", "CATASTROPHIZING_FORTUNE_TELLING",
                "DISQUALIFYING_DISCOUNTING_POSITIVE", "EMOTIONAL_REASONING", "LABELING",
                "MAGNIFICATION_MINIMIZATION", "MENTAL_FILTER_SELECTIVE_ABSTRACTION", "MIND_READING",
                "OVERGENERALIZATION", "PERSONALIZATION", "SHOULD_MUST_STATEMENTS", "TUNNEL_VISION"};
        for (int i = 0; i < codes.length; i++) {
            jdbc.update("""
                    INSERT INTO public.distortion_types(active,category_code,code,description,name_en,name_ko,sort_order,source_ref)
                    VALUES (true,'FIXTURE',?,'Synthetic','Fixture','Fixture',?,'TEST')
                    """, codes[i], i + 1);
        }
        jdbc.execute("""
                ALTER SEQUENCE public.emotion_records_id_seq RESTART WITH 10001;
                ALTER SEQUENCE public.reflection_sessions_id_seq RESTART WITH 20001;
                INSERT INTO public.emotion_records(ai_meta,completion_status,created_at,details,input_type,occurred_at,
                raw_text,secondary_emotions,time_bucket,updated_at,weekday_type,user_id)
                SELECT '{}'::jsonb,'QUICK',now(),'{}'::jsonb,'TEXT',now(),'Preservation fixture','[]'::jsonb,
                       'MORNING',now(),'WEEKDAY',u FROM generate_series(1,2) u;
                """);
        String usersBefore = fingerprint(jdbc, "users", "true");
        String existingBefore = fingerprint(jdbc, "emotion_records", "ai_meta='{}'::jsonb");
        initializer.run(null);
        assertThat(count(jdbc, "emotion_records")).isEqualTo(1002);
        assertThat(count(jdbc, "reflection_sessions")).isEqualTo(200);
        assertThat(count(jdbc, "session_distortions")).isEqualTo(343);
        assertThat(fingerprint(jdbc, "users", "true")).isEqualTo(usersBefore);
        assertThat(fingerprint(jdbc, "emotion_records", "ai_meta='{}'::jsonb")).isEqualTo(existingBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.emotion_records WHERE vector_dims(search_embedding)=1536", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.reflection_sessions WHERE vector_dims(context_embedding)=1536 AND vector_dims(thought_aware_embedding)=1536", Long.class)).isEqualTo(200);
        String records = fingerprint(jdbc, "emotion_records", "true");
        String sessions = fingerprint(jdbc, "reflection_sessions", "true");
        String distortions = fingerprint(jdbc, "session_distortions", "true");
        initializer.run(null);
        assertThat(fingerprint(jdbc, "emotion_records", "true")).isEqualTo(records);
        assertThat(fingerprint(jdbc, "reflection_sessions", "true")).isEqualTo(sessions);
        assertThat(fingerprint(jdbc, "session_distortions", "true")).isEqualTo(distortions);

        // A later conflict must roll back an earlier missing seed row's attempted insertion.
        jdbc.update("DELETE FROM public.emotion_records WHERE ai_meta->'demoSeed'->>'seedKey'='er-0001'");
        jdbc.update("UPDATE public.emotion_records SET raw_text='Edited demo fixture' WHERE ai_meta->'demoSeed'->>'seedKey'='er-1000'");
        String beforeConflict = fingerprint(jdbc, "emotion_records", "true");
        assertThat(assertThrows(SQLException.class, () -> initializer.run(null)).getMessage())
                .contains("Seed conflict");
        assertThat(fingerprint(jdbc, "emotion_records", "true")).isEqualTo(beforeConflict);
        assertThat(fingerprint(jdbc, "users", "true")).isEqualTo(usersBefore);
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM public." + table, Long.class);
    }

    private static String fingerprint(JdbcTemplate jdbc, String table, String condition) {
        return jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(t)::text,',' ORDER BY id)) FROM public."
                + table + " t WHERE " + condition, String.class);
    }
}
