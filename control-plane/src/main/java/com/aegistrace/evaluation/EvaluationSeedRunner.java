package com.aegistrace.evaluation;

import com.aegistrace.common.Jsons;
import com.aegistrace.config.AppProperties;
import com.aegistrace.seed.DevSeedRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Component
@Order(1)
public class EvaluationSeedRunner implements ApplicationRunner {
    private final AppProperties properties;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EvaluationSeedRunner(AppProperties properties, NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isSeedEnabled()) {
            return;
        }
        Integer workspace = jdbc.queryForObject(
                "select count(*)::int from workspaces where id = :id",
                Map.of("id", DevSeedRunner.WORKSPACE), Integer.class);
        if (workspace == null || workspace == 0) {
            return;
        }
        int version = EvaluationFixtures.VERSION;
        UUID suiteId = id("suite:" + EvaluationFixtures.SUITE_KEY + ":" + version);
        Integer exists = jdbc.queryForObject(
                "select count(*)::int from evaluation_suites where id = :id",
                Map.of("id", suiteId), Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.update("""
                insert into evaluation_suites (
                    id, workspace_id, suite_key, version_number, name, description, enabled, fixture_source, created_by
                ) values (
                    :id, :workspace, :key, :version, 'Support safety regression',
                    'Deterministic development fixtures for grounding, policy, abstention, tools, and prompt injection.',
                    true, 'DEVELOPMENT_FIXTURE', :user
                )
                """, Map.of(
                "id", suiteId,
                "workspace", DevSeedRunner.WORKSPACE,
                "key", EvaluationFixtures.SUITE_KEY,
                "version", version,
                "user", DevSeedRunner.DEVELOPER));
        int position = 0;
        for (EvaluationFixtures.Fixture fixture : EvaluationFixtures.cases()) {
            UUID caseId = id("case:" + fixture.key() + ":" + version);
            jdbc.update("""
                    insert into evaluation_cases (
                        id, workspace_id, case_key, version_number, name, description, category, execution_type,
                        input_text, expectations, enabled, fixture_source, created_by
                    ) values (
                        :id, :workspace, :key, :version, :name, :description, :category, :executionType,
                        :input, :expectations, true, 'DEVELOPMENT_FIXTURE', :user
                    ) on conflict (id) do nothing
                    """, new MapSqlParameterSource()
                    .addValue("id", caseId)
                    .addValue("workspace", DevSeedRunner.WORKSPACE)
                    .addValue("key", fixture.key())
                    .addValue("version", version)
                    .addValue("name", fixture.name())
                    .addValue("description", "Observable, deterministic " + fixture.category().toLowerCase() + " check.")
                    .addValue("category", fixture.category())
                    .addValue("executionType", fixture.executionType())
                    .addValue("input", fixture.input())
                    .addValue("expectations", Jsons.jsonb(Jsons.write(mapper, fixture.expectations())))
                    .addValue("user", DevSeedRunner.DEVELOPER));
            jdbc.update("""
                    insert into evaluation_suite_cases (suite_id, case_id, position)
                    values (:suite, :case, :position)
                    on conflict (suite_id, case_id) do nothing
                    """, Map.of("suite", suiteId, "case", caseId, "position", position++));
        }
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(("aegistrace:" + value).getBytes(StandardCharsets.UTF_8));
    }
}
