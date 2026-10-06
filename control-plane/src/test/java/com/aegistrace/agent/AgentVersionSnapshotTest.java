package com.aegistrace.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentVersionSnapshotTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aegis")
            .withUsername("aegis")
            .withPassword("aegis");

    @Test
    void activatingALaterVersionLeavesHistoricalRunSnapshotsUnchanged() throws Exception {
        try (Connection connection = connect()) {
            schema(connection);
            UUID v1 = UUID.randomUUID();
            UUID v2 = UUID.randomUUID();
            UUID run = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            exec(connection, "insert into agent_versions (id, agent_id, version_number, current_version, snapshot) values (?,?,1,true,'{\"version\":1}'::jsonb)",
                    v1, agent);
            exec(connection, "insert into agent_runs (id, agent_id, agent_version_id, snapshot) values (?,?,?,'{\"version\":1}'::jsonb)",
                    run, agent, v1);
            exec(connection, "insert into agent_versions (id, agent_id, version_number, current_version, snapshot) values (?,?,2,false,'{\"version\":2}'::jsonb)",
                    v2, agent);
            connection.setAutoCommit(false);
            exec(connection, "update agent_versions set current_version = false where agent_id = ?", agent);
            exec(connection, "update agent_versions set current_version = true where id = ?", v2);
            connection.commit();
            connection.setAutoCommit(true);

            try (var ps = connection.prepareStatement("select agent_version_id::text, snapshot->>'version' from agent_runs where id = ?")) {
                ps.setObject(1, run);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(v1.toString(), rs.getString(1));
                    assertEquals("1", rs.getString(2));
                }
            }
            try (var ps = connection.prepareStatement("select id::text from agent_versions where agent_id = ? and current_version")) {
                ps.setObject(1, agent);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(v2.toString(), rs.getString(1));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void onlyOneCurrentVersionCanExistPerAgent() throws Exception {
        try (Connection connection = connect()) {
            schema(connection);
            UUID agent = UUID.randomUUID();
            exec(connection, "insert into agent_versions (id, agent_id, version_number, current_version, snapshot) values (?,?,1,true,'{\"version\":1}'::jsonb)",
                    UUID.randomUUID(), agent);
            SQLException ex = assertThrows(SQLException.class, () ->
                    exec(connection, "insert into agent_versions (id, agent_id, version_number, current_version, snapshot) values (?,?,2,true,'{\"version\":2}'::jsonb)",
                            UUID.randomUUID(), agent));
            assertTrue(ex.getMessage() != null && ex.getMessage().toLowerCase().contains("agent_versions_one_current"));
        }
    }

    private Connection connect() throws SQLException {
        POSTGRES.start();
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void schema(Connection connection) throws SQLException {
        try (var st = connection.createStatement()) {
            st.execute("drop table if exists agent_runs");
            st.execute("drop table if exists agent_versions");
            st.execute("""
                    create table agent_versions (
                        id uuid primary key,
                        agent_id uuid not null,
                        version_number int not null,
                        current_version boolean not null,
                        snapshot jsonb not null
                    )
                    """);
            st.execute("create unique index agent_versions_one_current_idx on agent_versions (agent_id) where current_version");
            st.execute("""
                    create table agent_runs (
                        id uuid primary key,
                        agent_id uuid not null,
                        agent_version_id uuid not null,
                        snapshot jsonb not null
                    )
                    """);
        }
    }

    private void exec(Connection connection, String sql, Object... args) throws SQLException {
        try (var ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
