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
                    target oid := to_regclass('ai_jobs');
                    schema_name text;
                    table_name text;
                    operation_column smallint;
                    old_check record;
                BEGIN
                    IF target IS NULL THEN
                        RAISE EXCEPTION 'ai_jobs must exist before operation CHECK initialization';
                    END IF;
                    SELECT n.nspname, c.relname INTO schema_name, table_name
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.oid = target AND c.relkind = 'r';
                    IF NOT FOUND THEN
                        RAISE EXCEPTION 'Expected an ordinary ai_jobs table';
                    END IF;

                    -- Serialize concurrent application starts; reread under the lock.
                    EXECUTE format('LOCK TABLE %I.%I IN ACCESS EXCLUSIVE MODE', schema_name, table_name);
                    SELECT attnum INTO operation_column FROM pg_attribute
                    WHERE attrelid = target AND attname = 'operation' AND NOT attisdropped;
                    SELECT pg_get_expr(conbin, conrelid) AS expression,
                           conkey, contype, conislocal, coninhcount, connoinherit, convalidated
                    INTO old_check FROM pg_constraint
                    WHERE conrelid = target AND conname = 'ai_jobs_operation_check';

                    -- No such restriction means there is nothing to widen.
                    IF NOT FOUND THEN RETURN; END IF;
                    IF old_check.contype <> 'c'
                       OR old_check.conkey IS DISTINCT FROM ARRAY[operation_column]::smallint[]
                       OR NOT old_check.conislocal OR old_check.coninhcount <> 0 THEN
                        RAISE EXCEPTION 'Unexpected ai_jobs_operation_check shape; refusing automatic replacement';
                    END IF;
                    IF position(quote_literal('CBT_COMMAND') IN old_check.expression) > 0 THEN
                        RETURN;
                    END IF;

                    -- Preserve every old allowed value and only add CBT_COMMAND.
                    -- Both clauses are atomic; no committed interval without a CHECK.
                    EXECUTE format(
                        'ALTER TABLE %I.%I DROP CONSTRAINT %I, ADD CONSTRAINT %I CHECK ((%s) OR operation = %L)%s%s',
                        schema_name, table_name, 'ai_jobs_operation_check', 'ai_jobs_operation_check',
                        old_check.expression, 'CBT_COMMAND',
                        CASE WHEN old_check.connoinherit THEN ' NO INHERIT' ELSE '' END,
                        CASE WHEN old_check.convalidated THEN '' ELSE ' NOT VALID' END
                    );
                END;
                $mindot$;
                """);
    }
}
