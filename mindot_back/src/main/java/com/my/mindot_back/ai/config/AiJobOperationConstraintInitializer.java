package com.my.mindot_back.ai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Supplements Hibernate update for the existing PostgreSQL enum CHECK. */
@Component
@RequiredArgsConstructor
@DependsOn("entityManagerFactory")
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "spring.jpa.hibernate.ddl-auto", havingValue = "update")
public class AiJobOperationConstraintInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // DDL failure rolls back the whole change and fails startup, before readiness.
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        jdbc.execute("SET LOCAL statement_timeout = '30s'");
        jdbc.execute("""
                DO $mindot$
                DECLARE
                    target oid;
                    spec record;
                    schema_name text;
                    table_name text;
                    operation_column smallint;
                    old_check record;
                BEGIN
                  FOR spec IN SELECT * FROM (VALUES
                    ('ai_jobs', 'operation', 'ai_jobs_operation_check', 'CBT_COMMAND'),
                    ('reflection_sessions', 'status', 'reflection_sessions_status_check', 'SAFETY_STOPPED')
                  ) AS required(table_name, column_name, constraint_name, new_value) LOOP
                    target := to_regclass(spec.table_name);
                    IF target IS NULL THEN
                        RAISE EXCEPTION 'Table % must exist before CHECK initialization', spec.table_name;
                    END IF;
                    SELECT n.nspname, c.relname INTO schema_name, table_name
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.oid = target AND c.relkind = 'r';
                    IF NOT FOUND THEN
                        RAISE EXCEPTION 'Expected an ordinary table: %', spec.table_name;
                    END IF;

                    -- Serialize concurrent application starts; reread under the lock.
                    EXECUTE format('LOCK TABLE %I.%I IN ACCESS EXCLUSIVE MODE', schema_name, table_name);
                    SELECT attnum INTO operation_column FROM pg_attribute
                    WHERE attrelid = target AND attname = spec.column_name AND NOT attisdropped;
                    SELECT pg_get_expr(conbin, conrelid) AS expression,
                           conkey, contype, conislocal, coninhcount, connoinherit, convalidated
                    INTO old_check FROM pg_constraint
                    WHERE conrelid = target AND conname = spec.constraint_name;

                    -- A missing or renamed CHECK is not evidence of compatibility.
                    IF NOT FOUND THEN
                        RAISE EXCEPTION 'Expected CHECK % is absent; inspect the database definition', spec.constraint_name;
                    END IF;
                    IF old_check.contype <> 'c'
                       OR old_check.conkey IS DISTINCT FROM ARRAY[operation_column]::smallint[]
                       OR NOT old_check.conislocal OR old_check.coninhcount <> 0 THEN
                        RAISE EXCEPTION 'Unexpected CHECK shape: %; refusing automatic replacement', spec.constraint_name;
                    END IF;
                    IF position(quote_literal(spec.new_value) IN old_check.expression) > 0 THEN
                        CONTINUE;
                    END IF;

                    -- Preserve every old allowed value and only add the required enum value.
                    -- Both clauses are atomic; no committed interval without a CHECK.
                    EXECUTE format(
                        'ALTER TABLE %I.%I DROP CONSTRAINT %I, ADD CONSTRAINT %I CHECK ((%s) OR %I = %L)%s%s',
                        schema_name, table_name, spec.constraint_name, spec.constraint_name,
                        old_check.expression, spec.column_name, spec.new_value,
                        CASE WHEN old_check.connoinherit THEN ' NO INHERIT' ELSE '' END,
                        CASE WHEN old_check.convalidated THEN '' ELSE ' NOT VALID' END
                    );
                  END LOOP;
                END;
                $mindot$;
                """);
    }
}
