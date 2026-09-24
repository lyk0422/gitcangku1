package com.example.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
 * 锁文件重解析的真实 HTTP 入口测试：状态码、JSON 结构、查询与幂等重放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReresolveControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void register(String name, int version, Object... dependencies) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(dependencies));
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private static Map<String, Object> dependency(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private long createLock(String rootName, int rootVersion, long expectedVersion) {
        Map<String, Object> body = Map.of(
                "rootName", rootName,
                "rootVersion", rootVersion,
                "expectedRepositoryVersion", expectedVersion);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(toJson(body), jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asLong();
    }

    private ResponseEntity<JsonNode> postReresolve(long lockId, String requestId,
                                                   String key, long expectedVersion) {
        Map<String, Object> body = Map.of(
                "reresolveKey", key,
                "expectedRepositoryVersion", expectedVersion);
        return restTemplate.postForEntity(
                "/api/artifacts/locks/" + lockId + "/reresolves",
                new HttpEntity<>(toJson(body), jsonHeaders(requestId)), JsonNode.class);
    }

    @Test
    void fullReresolveWorkflowReturnsReportAndSupportsQueries() {
        register("app", 1, dependency("lib", 1, 2));
        register("lib", 1);
        long lockId = createLock("app", 1, 2);
        register("lib", 2);

        ResponseEntity<JsonNode> response = postReresolve(lockId, UUID.randomUUID().toString(),
                "http-key-1", 3);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode report = response.getBody();
        long reportId = report.path("id").asLong();
        assertThat(report.path("reresolveKey").asText()).isEqualTo("http-key-1");
        assertThat(report.path("lockFileId").asLong()).isEqualTo(lockId);
        assertThat(report.path("rootName").asText()).isEqualTo("app");
        assertThat(report.path("rootVersion").asInt()).isEqualTo(1);
        assertThat(report.path("repositoryVersion").asLong()).isEqualTo(3L);
        assertThat(report.path("conclusion").asText()).isEqualTo("DRIFTED");
        assertThat(report.path("newEntries").get(1).path("version").asInt()).isEqualTo(2);
        JsonNode diff = report.path("diffs").get(0);
        assertThat(diff.path("name").asText()).isEqualTo("lib");
        assertThat(diff.path("changeType").asText()).isEqualTo("VERSION_CHANGED");
        assertThat(diff.path("reason").asText()).isEqualTo("SUPERSEDED_BY_HIGHER");
        assertThat(diff.path("originalVersion").asInt()).isEqualTo(1);
        assertThat(diff.path("newVersion").asInt()).isEqualTo(2);

        ResponseEntity<JsonNode> one = restTemplate.getForEntity(
                "/api/artifacts/reresolves/{id}", JsonNode.class, reportId);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody().path("id").asLong()).isEqualTo(reportId);

        ResponseEntity<JsonNode> list = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}/reresolves", JsonNode.class, lockId);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().isArray()).isTrue();
        assertThat(list.getBody()).hasSize(1);
    }

    @Test
    void infeasibleConclusionReturns201WithBlocker() {
        register("app", 1, dependency("lib", 1, 1));
        register("lib", 1);
        long lockId = createLock("app", 1, 2);

        restTemplate.exchange("/api/artifacts/lib/versions/1/withdraw",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        ResponseEntity<JsonNode> response = postReresolve(lockId, UUID.randomUUID().toString(),
                "http-key-infeasible", 3);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().path("conclusion").asText()).isEqualTo("INFEASIBLE");
        assertThat(response.getBody().path("newEntries").isArray()).isTrue();
        assertThat(response.getBody().path("newEntries")).hasSize(0);
        assertThat(response.getBody().path("diffs").get(0).path("name").asText())
                .isEqualTo("lib");
        assertThat(response.getBody().path("diffs").get(0).path("reason").asText())
                .isEqualTo("VERSIONS_WITHDRAWN");
    }

    @Test
    void errorBranchesReturnExpectedStatus() {
        register("app", 1);
        long lockId = createLock("app", 1, 1);

        // 锁文件不存在 → 404。
        ResponseEntity<JsonNode> missingLock = postReresolve(9999, UUID.randomUUID().toString(),
                "http-key-missing", 1);
        assertThat(missingLock.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 仓库版本不符 → 409。
        ResponseEntity<JsonNode> stale = postReresolve(lockId, UUID.randomUUID().toString(),
                "http-key-stale", 5);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 根版本已撤回 → 422。
        restTemplate.exchange("/api/artifacts/app/versions/1/withdraw",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        ResponseEntity<JsonNode> withdrawnRoot = postReresolve(lockId,
                UUID.randomUUID().toString(), "http-key-root", 2);
        assertThat(withdrawnRoot.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 查询不存在的报告 → 404。
        ResponseEntity<JsonNode> missingReport = restTemplate.getForEntity(
                "/api/artifacts/reresolves/9999", JsonNode.class);
        assertThat(missingReport.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sameRequestIdReplaysAndDifferentParamsConflict() {
        register("app", 1);
        long lockId = createLock("app", 1, 1);

        String rid = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = postReresolve(lockId, rid, "http-key-replay", 1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> replay = postReresolve(lockId, rid, "http-key-replay", 1);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("id").asLong())
                .isEqualTo(first.getBody().path("id").asLong());

        ResponseEntity<JsonNode> different = postReresolve(lockId, rid, "http-key-replay", 99);
        assertThat(different.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
