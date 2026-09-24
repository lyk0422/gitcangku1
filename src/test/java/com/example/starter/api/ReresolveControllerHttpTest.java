package com.example.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重解析 REST 入口测试：状态码、X-Reresolve-Key 头、报告 JSON 结构与查询端点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReresolveControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM reresolve_report_diff");
        jdbcTemplate.update("DELETE FROM reresolve_report_entry");
        jdbcTemplate.update("DELETE FROM reresolve_report");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static HttpHeaders jsonHeaders(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            headers.set("X-Request-Id", requestId);
        }
        return headers;
    }

    private static HttpHeaders reresolveHeaders(String reresolveKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (reresolveKey != null) {
            headers.set("X-Reresolve-Key", reresolveKey);
        }
        return headers;
    }

    private String registerBody(String name, int version, Object... dependencies) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(dependencies));
        return toJson(body);
    }

    private Map<String, Object> dependency(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void register(String name, int version, Object... dependencies) {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody(name, version, dependencies),
                        jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
    }

    private long lock(String rootName, int rootVersion, long expectedVersion) {
        String body = "{\"rootName\":\"" + rootName + "\",\"rootVersion\":" + rootVersion
                + ",\"expectedRepositoryVersion\":" + expectedVersion + "}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asLong();
    }

    private ResponseEntity<JsonNode> reresolve(String key, long lockId, long expectedVersion) {
        String body = "{\"lockId\":" + lockId
                + ",\"expectedRepositoryVersion\":" + expectedVersion + "}";
        return restTemplate.postForEntity("/api/artifacts/locks/reresolve",
                new HttpEntity<>(body, reresolveHeaders(key)), JsonNode.class);
    }

    @Test
    void reresolveViaHttpReturns201WithReportStructure() {
        register("app", 1, dependency("lib", 1, 2));
        register("lib", 1);
        long lockId = lock("app", 1, 2L);
        register("lib", 2);

        ResponseEntity<JsonNode> response = reresolve(UUID.randomUUID().toString(), lockId, 3L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode report = response.getBody();
        assertThat(report.path("lockFileId").asLong()).isEqualTo(lockId);
        assertThat(report.path("repositoryVersion").asLong()).isEqualTo(3L);
        assertThat(report.path("conclusion").asText()).isEqualTo("DRIFTED");
        assertThat(report.path("entries")).hasSize(2);
        assertThat(report.path("diffs")).hasSize(1);
        JsonNode diff = report.path("diffs").get(0);
        assertThat(diff.path("name").asText()).isEqualTo("lib");
        assertThat(diff.path("changeType").asText()).isEqualTo("CHANGED");
        assertThat(diff.path("reason").asText()).isEqualTo("SUPERSEDED");
        assertThat(diff.path("oldVersion").asInt()).isEqualTo(1);
        assertThat(diff.path("newVersion").asInt()).isEqualTo(2);

        // 明细查询与按锁文件列表查询。
        long reportId = report.path("id").asLong();
        ResponseEntity<JsonNode> one = restTemplate.getForEntity(
                "/api/artifacts/locks/reresolve-reports/{id}", JsonNode.class, reportId);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody().path("conclusion").asText()).isEqualTo("DRIFTED");

        ResponseEntity<JsonNode> list = restTemplate.getForEntity(
                "/api/artifacts/locks/{lockId}/reresolve-reports", JsonNode.class, lockId);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);
        assertThat(list.getBody().get(0).path("id").asLong()).isEqualTo(reportId);

        // 原锁文件未被改写。
        ResponseEntity<JsonNode> lockAfter = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}", JsonNode.class, lockId);
        assertThat(lockAfter.getBody().path("entries").get(1).path("version").asInt())
                .isEqualTo(1);
    }

    @Test
    void reresolveReproducibleAndInfeasibleConclusions() {
        register("app", 1, dependency("lib", 1, 1));
        register("lib", 1);
        long lockId = lock("app", 1, 2L);

        ResponseEntity<JsonNode> reproducible = reresolve(
                UUID.randomUUID().toString(), lockId, 2L);
        assertThat(reproducible.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reproducible.getBody().path("conclusion").asText())
                .isEqualTo("REPRODUCIBLE");
        assertThat(reproducible.getBody().path("diffs")).isEmpty();

        // 撤回唯一候选后重解析 → INFEASIBLE，阻塞项给出已撤回版本。
        restTemplate.exchange("/api/artifacts/lib/versions/1/withdraw", HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        ResponseEntity<JsonNode> infeasible = reresolve(
                UUID.randomUUID().toString(), lockId, 3L);
        assertThat(infeasible.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(infeasible.getBody().path("conclusion").asText()).isEqualTo("INFEASIBLE");
        JsonNode blocker = infeasible.getBody().path("diffs").get(0);
        assertThat(blocker.path("name").asText()).isEqualTo("lib");
        assertThat(blocker.path("changeType").asText()).isEqualTo("BLOCKER");
        assertThat(blocker.path("reason").asText()).isEqualTo("VERSION_WITHDRAWN");
        assertThat(blocker.path("versions").get(0).asInt()).isEqualTo(1);
    }

    @Test
    void reresolveErrorBranches() {
        register("app", 1);
        long lockId = lock("app", 1, 1L);
        register("lib", 1);

        // 仓库版本不符 → 409。
        ResponseEntity<JsonNode> stale = reresolve(UUID.randomUUID().toString(), lockId, 1L);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 锁文件不存在 → 404。
        ResponseEntity<JsonNode> missing = reresolve(UUID.randomUUID().toString(), 9999L, 2L);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 根版本已撤回 → 422。
        restTemplate.exchange("/api/artifacts/app/versions/1/withdraw", HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        ResponseEntity<JsonNode> withdrawnRoot = reresolve(
                UUID.randomUUID().toString(), lockId, 3L);
        assertThat(withdrawnRoot.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 缺少 X-Reresolve-Key 头 → 400。
        ResponseEntity<JsonNode> noKey = restTemplate.postForEntity(
                "/api/artifacts/locks/reresolve",
                new HttpEntity<>("{\"lockId\":" + lockId + ",\"expectedRepositoryVersion\":3}",
                        reresolveHeaders(null)),
                JsonNode.class);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 报告不存在 → 404。
        ResponseEntity<JsonNode> missingReport = restTemplate.getForEntity(
                "/api/artifacts/locks/reresolve-reports/9999", JsonNode.class);
        assertThat(missingReport.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reresolveKeyReplayAndConflictViaHttp() {
        register("app", 1, dependency("lib", 1, 1));
        register("lib", 1);
        long lockId = lock("app", 1, 2L);

        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = reresolve(key, lockId, 2L);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 同键同参重放首次响应快照。
        ResponseEntity<JsonNode> replay = reresolve(key, lockId, 2L);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("id").asLong())
                .isEqualTo(first.getBody().path("id").asLong());

        // 同键异参 → 409。
        ResponseEntity<JsonNode> conflict = reresolve(key, lockId, 0L);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
