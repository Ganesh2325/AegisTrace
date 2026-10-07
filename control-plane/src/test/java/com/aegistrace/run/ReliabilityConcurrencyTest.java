package com.aegistrace.run;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReliabilityConcurrencyTest {
    @BeforeEach
    void schema() throws Exception {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            statement.execute("drop table if exists reliability_tool_executions");
            statement.execute("drop table if exists reliability_runs");
            statement.execute("drop table if exists reliability_approvals");
            statement.execute("drop table if exists reliability_jobs");
            statement.execute("create table reliability_jobs (id uuid primary key, status text, locked_by text)");
            statement.execute("create table reliability_approvals (id uuid primary key, status text)");
            statement.execute("create table reliability_runs (id uuid primary key, state text)");
            statement.execute("create table reliability_tool_executions (run_id uuid unique, status text)");
        }
    }

    @Test
    void staleWorkerCannotCompleteWorkOwnedByAnotherWorker() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = connect()) {
            try (var insert = connection.prepareStatement(
                    "insert into reliability_jobs (id, status, locked_by) values (?, 'RUNNING', 'worker-b')")) {
                insert.setObject(1, id);
                insert.executeUpdate();
            }
            try (var stale = connection.prepareStatement("""
                    update reliability_jobs set status = 'SUCCEEDED'
                    where id = ? and status = 'RUNNING' and locked_by = 'worker-a'
                    """)) {
                stale.setObject(1, id);
                assertEquals(0, stale.executeUpdate());
            }
            try (var owner = connection.prepareStatement("""
                    update reliability_jobs set status = 'SUCCEEDED'
                    where id = ? and status = 'RUNNING' and locked_by = 'worker-b'
                    """)) {
                owner.setObject(1, id);
                assertEquals(1, owner.executeUpdate());
            }
        }
    }

    @Test
    void simultaneousOppositeApprovalDecisionsHaveOneWinner() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = connect(); var insert = connection.prepareStatement(
                "insert into reliability_approvals (id, status) values (?, 'PENDING')")) {
            insert.setObject(1, id);
            insert.executeUpdate();
        }
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approve = executor.submit(() -> decide(id, "APPROVED", start));
            var reject = executor.submit(() -> decide(id, "REJECTED", start));
            start.countDown();
            Set<Integer> changes = Set.of(approve.get(), reject.get());
            assertEquals(Set.of(0, 1), changes);
        }
    }

    @Test
    void cancellationCannotHideACommittedSideEffect() throws Exception {
        UUID runId = UUID.randomUUID();
        try (Connection connection = connect(); var insert = connection.prepareStatement(
                "insert into reliability_runs (id, state) values (?, 'TOOL_EXECUTING')")) {
            insert.setObject(1, runId);
            insert.executeUpdate();
        }
        var sideEffectLocked = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var sideEffect = executor.submit(() -> {
                try (Connection connection = connect()) {
                    connection.setAutoCommit(false);
                    try (var lock = connection.prepareStatement(
                            "select state from reliability_runs where id = ? for update")) {
                        lock.setObject(1, runId);
                        lock.executeQuery();
                    }
                    try (var insert = connection.prepareStatement(
                            "insert into reliability_tool_executions (run_id, status) values (?, 'SUCCEEDED')")) {
                        insert.setObject(1, runId);
                        insert.executeUpdate();
                    }
                    sideEffectLocked.countDown();
                    allowCommit.await();
                    connection.commit();
                    return 1;
                }
            });
            var cancellation = executor.submit(() -> {
                sideEffectLocked.await();
                try (Connection connection = connect()) {
                    connection.setAutoCommit(false);
                    try (var lock = connection.prepareStatement(
                            "select state from reliability_runs where id = ? for update")) {
                        lock.setObject(1, runId);
                        lock.executeQuery();
                    }
                    int committed;
                    try (var query = connection.prepareStatement(
                            "select count(*) from reliability_tool_executions where run_id = ? and status = 'SUCCEEDED'")) {
                        query.setObject(1, runId);
                        try (var result = query.executeQuery()) {
                            result.next();
                            committed = result.getInt(1);
                        }
                    }
                    int changed = 0;
                    if (committed == 0) {
                        try (var update = connection.prepareStatement(
                                "update reliability_runs set state = 'CANCELLED' where id = ?")) {
                            update.setObject(1, runId);
                            changed = update.executeUpdate();
                        }
                    }
                    connection.commit();
                    return changed;
                }
            });
            allowCommit.countDown();
            assertEquals(1, sideEffect.get());
            assertEquals(0, cancellation.get());
        }
    }

    private int decide(UUID id, String decision, CountDownLatch start) throws Exception {
        start.await();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            String status;
            try (var select = connection.prepareStatement(
                    "select status from reliability_approvals where id = ? for update")) {
                select.setObject(1, id);
                try (var result = select.executeQuery()) {
                    result.next();
                    status = result.getString(1);
                }
            }
            int changed = 0;
            if ("PENDING".equals(status)) {
                try (var update = connection.prepareStatement(
                        "update reliability_approvals set status = ? where id = ? and status = 'PENDING'")) {
                    update.setString(1, decision);
                    update.setObject(2, id);
                    changed = update.executeUpdate();
                }
            }
            connection.commit();
            return changed;
        }
    }

    private Connection connect() throws Exception {
        String url = System.getenv("AEGIS_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "AEGIS_TEST_JDBC_URL is required");
        return DriverManager.getConnection(
                url,
                env("AEGIS_TEST_DB_USER", "aegis"),
                env("AEGIS_TEST_DB_PASSWORD", "aegis"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
