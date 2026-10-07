package com.aegistrace.run;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class RunRecovery {
    static final int RECOVERY_BATCH = 50;

    private final NamedParameterJdbcTemplate jdbc;
    private final RunService runs;

    public RunRecovery(NamedParameterJdbcTemplate jdbc, RunService runs) {
        this.jdbc = jdbc;
        this.runs = runs;
    }

    /**
     * QUEUED runs are safe to replay because orchestration first performs an
     * atomic QUEUED -> RUNNING transition. This also recovers work left behind
     * by a control-plane restart without replaying a partially executed run.
     */
    @Scheduled(initialDelay = 2_000, fixedDelay = 15_000)
    public void recoverQueuedRuns() {
        jdbc.query("""
                select id from agent_runs
                where state = 'QUEUED' and timeout_at > now()
                order by created_at
                limit :limit
                """, Map.of("limit", RECOVERY_BATCH),
                (rs, n) -> UUID.fromString(rs.getString("id")))
                .forEach(runs::recoverQueued);
    }
}
