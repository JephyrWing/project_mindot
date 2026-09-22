package com.my.mindot_back.seed;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DemoSeedInitializerTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean("entityManagerFactory", Object.class, Object::new)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withUserConfiguration(DemoSeedInitializer.class);

    @Test
    void enabledLocallyByDefaultAndCanBeDisabled() {
        context.run(c -> assertThat(c).hasSingleBean(DemoSeedInitializer.class));
        context.withPropertyValues("mindot.demo-seed.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(DemoSeedInitializer.class));
        context.withPropertyValues("mindot.demo-seed.enabled=true")
                .run(c -> assertThat(c).hasSingleBean(DemoSeedInitializer.class));
    }

    @Test
    void jdbcFailureRollsBackAndPreventsSuccessfulStartup() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        ResultSet user = mock(ResultSet.class);
        when(statement.executeQuery(anyString())).thenReturn(user);
        when(user.next()).thenReturn(true);
        when(user.getBoolean(1)).thenReturn(true);
        SQLException conflict = new SQLException("Seed conflict", "P0001");
        when(statement.execute(anyString())).thenThrow(conflict);

        assertSame(conflict, assertThrows(SQLException.class,
                () -> new DemoSeedInitializer(dataSource).run(null)));
        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }
}
