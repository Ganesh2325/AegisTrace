package com.aegistrace.knowledge;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeVersionIsolationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aegis")
            .withUsername("aegis")
            .withPassword("aegis");

    @Test
    void publishedVersionMembershipDoesNotGainLaterDocuments() throws Exception {
        try (Connection connection = connect()) {
            schema(connection);
            UUID kb = UUID.randomUUID();
            UUID v1 = UUID.randomUUID();
            UUID v2 = UUID.randomUUID();
            UUID doc1 = UUID.randomUUID();
            UUID doc2 = UUID.randomUUID();
            exec(connection, "insert into knowledge_bases (id) values (?)", kb);
            exec(connection, "insert into documents (id, knowledge_base_id, status) values (?,?, 'ACTIVE')", doc1, kb);
            exec(connection, "insert into knowledge_base_versions (id, knowledge_base_id, version_number, current_version) values (?,?,1,true)", v1, kb);
            exec(connection, "insert into knowledge_version_documents (knowledge_base_version_id, document_id) values (?,?)", v1, doc1);
            exec(connection, "insert into documents (id, knowledge_base_id, status) values (?,?, 'ACTIVE')", doc2, kb);
            exec(connection, "insert into knowledge_base_versions (id, knowledge_base_id, version_number, current_version) values (?,?,2,false)", v2, kb);
            exec(connection, "insert into knowledge_version_documents (knowledge_base_version_id, document_id) values (?,?)", v2, doc1);
            exec(connection, "insert into knowledge_version_documents (knowledge_base_version_id, document_id) values (?,?)", v2, doc2);
            connection.setAutoCommit(false);
            exec(connection, "update knowledge_base_versions set current_version = false where knowledge_base_id = ?", kb);
            exec(connection, "update knowledge_base_versions set current_version = true where id = ?", v2);
            connection.commit();

            try (var ps = connection.prepareStatement("""
                    select document_id::text from knowledge_version_documents where knowledge_base_version_id = ? order by document_id::text
                    """)) {
                ps.setObject(1, v1);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(doc1.toString(), rs.getString(1));
                    assertFalse(rs.next());
                }
            }
            try (var ps = connection.prepareStatement("select count(*) from knowledge_version_documents where knowledge_base_version_id = ?")) {
                ps.setObject(1, v2);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void runKeepsPinnedKnowledgeVersionAfterLaterPublish() throws Exception {
        try (Connection connection = connect()) {
            schema(connection);
            UUID kb = UUID.randomUUID();
            UUID v1 = UUID.randomUUID();
            UUID v2 = UUID.randomUUID();
            UUID run = UUID.randomUUID();
            exec(connection, "insert into knowledge_bases (id) values (?)", kb);
            exec(connection, "insert into knowledge_base_versions (id, knowledge_base_id, version_number, current_version) values (?,?,1,true)", v1, kb);
            exec(connection, "insert into agent_runs (id, knowledge_base_id, knowledge_base_version_id, snapshot) values (?,?,?,'{\"knowledgeBaseVersionId\":\"' || ? || '\"}'::jsonb)",
                    run, kb, v1, v1.toString());
            exec(connection, "insert into knowledge_base_versions (id, knowledge_base_id, version_number, current_version) values (?,?,2,false)", v2, kb);
            exec(connection, "update knowledge_base_versions set current_version = false where knowledge_base_id = ?", kb);
            exec(connection, "update knowledge_base_versions set current_version = true where id = ?", v2);
            try (var ps = connection.prepareStatement("select knowledge_base_version_id::text, snapshot->>'knowledgeBaseVersionId' from agent_runs where id = ?")) {
                ps.setObject(1, run);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(v1.toString(), rs.getString(1));
                    assertEquals(v1.toString(), rs.getString(2));
                }
            }
        }
    }

    private Connection connect() throws SQLException {
        POSTGRES.start();
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void schema(Connection connection) throws SQLException {
        try (var st = connection.createStatement()) {
            st.execute("drop table if exists agent_runs");
            st.execute("drop table if exists knowledge_version_documents");
            st.execute("drop table if exists knowledge_base_versions");
            st.execute("drop table if exists documents");
            st.execute("drop table if exists knowledge_bases");
            st.execute("create table knowledge_bases (id uuid primary key)");
            st.execute("create table documents (id uuid primary key, knowledge_base_id uuid not null, status text not null)");
            st.execute("""
                    create table knowledge_base_versions (
                        id uuid primary key,
                        knowledge_base_id uuid not null,
                        version_number int not null,
                        current_version boolean not null
                    )
                    """);
            st.execute("create unique index knowledge_base_versions_one_current_idx on knowledge_base_versions (knowledge_base_id) where current_version");
            st.execute("""
                    create table knowledge_version_documents (
                        knowledge_base_version_id uuid not null,
                        document_id uuid not null,
                        primary key (knowledge_base_version_id, document_id)
                    )
                    """);
            st.execute("""
                    create table agent_runs (
                        id uuid primary key,
                        knowledge_base_id uuid not null,
                        knowledge_base_version_id uuid not null,
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
