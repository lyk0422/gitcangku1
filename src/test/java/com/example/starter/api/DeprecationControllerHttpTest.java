package com.example.starter.api;

import com.example.starter.support.MutableClock;
import com.example.starter.support.MutableClockConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
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
 * 弃用窗口能力的真实 HTTP 入口测试：状态码、结构化错误明细、警告响应头与只读查询。
 * 时钟为可控 UTC 时钟，精确覆盖生效前后边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MutableClockConfiguration.class)
class DeprecationControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_deprecation");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM deprecation_event");
        jdbcTemplate.update("DELETE FROM deprecation_window");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
        clock.set(MutableClockConfiguration.T0);
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
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void registerArtifact(String name, int version, Object... dependencies) {
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

    private static Map<String, Object> window(String name, int version, int replacement,
                                               String effectiveAt, String note) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("name", name);
        w.put("version", version);
        w.put("replacementVersion", replacement);
        w.put("effectiveAt", effectiveAt);
        w.put("note", note);
        return w;
    }

    @Test
    void batchSetThenQueryDetailsAndEvents() {
        registerArtifact("lib", 1);
        registerArtifact("lib", 2);
        String body = toJson(Map.of("windows", List.of(
                window("lib", 1, 2, "2026-06-01T00:00:00Z", "迁移"))));

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/deprecations",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("requestedCount").asInt()).isEqualTo(1);
        assertThat(response.getBody().path("acceptedCount").asInt()).isEqualTo(1);
        assertThat(response.getBody().path("windows").get(0).path("status").asText())
                .isEqualTo("REGISTERED");

        ResponseEntity<JsonNode> detail = restTemplate.getForEntity(
                "/api/artifacts/deprecations/lib/versions/1", JsonNode.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().path("replacementVersion").asInt()).isEqualTo(2);

        ResponseEntity<JsonNode> events = restTemplate.getForEntity(
                "/api/artifacts/deprecations/events", JsonNode.class);
        assertThat(events.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(events.getBody()).hasSize(1);
        assertThat(events.getBody().get(0).path("action").asText()).isEqualTo("REGISTERED");
    }

    @Test
    void invalidReplacementReturns422WithDistinguishableCodeAndDetails() {
        registerArtifact("lib", 1);
        String body = toJson(Map.of("windows", List.of(
                window("lib", 1, 9, "2026-06-01T00:00:00Z", "x"))));

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/deprecations",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().path("error").asText()).isEqualTo("REPLACEMENT_MISSING");
        JsonNode details = response.getBody().path("details");
        assertThat(details.path("reason").asText()).isEqualTo("REPLACEMENT_MISSING");
        assertThat(details.path("name").asText()).isEqualTo("lib");
        assertThat(details.path("replacementVersion").asInt()).isEqualTo(9);
        assertThat(details.path("requestedCount").asInt()).isEqualTo(1);
        // 失败不留半成品。
        ResponseEntity<JsonNode> windows = restTemplate.getForEntity(
                "/api/artifacts/deprecations", JsonNode.class);
        assertThat(windows.getBody()).hasSize(0);
    }

    @Test
    void lockBeforeEffectiveReturns201WithWarningHeaderAndBody() {
        registerArtifact("app", 1, dependency("lib", 1, 2));
        registerArtifact("lib", 1);
        registerArtifact("lib", 2);
        restTemplate.postForEntity("/api/artifacts/deprecations",
                new HttpEntity<>(toJson(Map.of("windows", List.of(
                        window("lib", 2, 1, "2026-06-01T00:00:00Z", "lib2 弃用")))),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":4}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lock.getHeaders().getFirst("X-Deprecation-Warning"))
                .isEqualTo("DEPRECATION_WARNING");
        JsonNode warnings = lock.getBody().path("deprecationWarnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).path("name").asText()).isEqualTo("lib");
        assertThat(warnings.get(0).path("version").asInt()).isEqualTo(2);
        assertThat(warnings.get(0).path("secondsUntilEffective").asLong()).isPositive();
        assertThat(lock.getBody().path("resolvedAt").asText()).startsWith("2026-01-01T00:00:00");
    }

    @Test
    void lockAfterEffectiveReturns422WithSortedBlockedDetails() {
        registerArtifact("app", 1, dependency("zlib", 1, 1), dependency("alib", 1, 1));
        registerArtifact("zlib", 1);
        registerArtifact("zlib", 2);
        registerArtifact("alib", 1);
        registerArtifact("alib", 2);
        restTemplate.postForEntity("/api/artifacts/deprecations",
                new HttpEntity<>(toJson(Map.of("windows", List.of(
                        window("zlib", 1, 2, "2026-06-01T00:00:00Z", "z"),
                        window("alib", 1, 2, "2026-06-01T00:00:00Z", "a")))),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        clock.set(java.time.Instant.parse("2026-06-01T00:00:00Z"));

        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":6}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(lock.getBody().path("error").asText()).isEqualTo("DEPRECATION_EFFECTIVE");
        JsonNode blocked = lock.getBody().path("details").path("blocked");
        assertThat(blocked).hasSize(2);
        // 稳定排序：alib 先于 zlib。
        assertThat(blocked.get(0).path("name").asText()).isEqualTo("alib");
        assertThat(blocked.get(1).path("name").asText()).isEqualTo("zlib");
        assertThat(blocked.get(0).path("effectiveAt").asText())
                .isEqualTo("2026-06-01T00:00:00Z");
    }

    @Test
    void revokeWorkflowAndHistoryPreserved() {
        registerArtifact("lib", 1);
        registerArtifact("lib", 2);
        restTemplate.postForEntity("/api/artifacts/deprecations",
                new HttpEntity<>(toJson(Map.of("windows", List.of(
                        window("lib", 1, 2, "2026-06-01T00:00:00Z", "迁移")))),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        ResponseEntity<JsonNode> revoked = restTemplate.exchange(
                "/api/artifacts/deprecations/lib/versions/1/revoke",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().path("status").asText()).isEqualTo("REVOKED");
        assertThat(revoked.getBody().path("revokedAt").asText()).startsWith("2026-01-01");

        // 撤销后重复撤销 → 409。
        ResponseEntity<JsonNode> again = restTemplate.exchange(
                "/api/artifacts/deprecations/lib/versions/1/revoke",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 历史保留登记与撤销两条事件。
        ResponseEntity<JsonNode> events = restTemplate.getForEntity(
                "/api/artifacts/deprecations/lib/versions/1/events", JsonNode.class);
        assertThat(events.getBody()).hasSize(2);
        assertThat(events.getBody().get(1).path("action").asText()).isEqualTo("REVOKED");
        assertThat(events.getBody().get(1).path("note").asText()).isEqualTo("迁移");
    }

    @Test
    void diagnoseEndpointIsReadOnlyAndHonoursAtParameter() {
        registerArtifact("app", 1, dependency("lib", 1, 2));
        registerArtifact("lib", 1);
        registerArtifact("lib", 2);
        restTemplate.postForEntity("/api/artifacts/deprecations",
                new HttpEntity<>(toJson(Map.of("windows", List.of(
                        window("lib", 2, 1, "2026-06-01T00:00:00Z", "x")))),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        // 当前时刻：仅警告。
        ResponseEntity<JsonNode> now = restTemplate.getForEntity(
                "/api/artifacts/diagnose?rootName=app&rootVersion=1", JsonNode.class);
        assertThat(now.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(now.getBody().path("warnings")).hasSize(1);
        assertThat(now.getBody().path("blocked")).hasSize(0);

        // 指定未来时刻：阻断。
        ResponseEntity<JsonNode> future = restTemplate.getForEntity(
                "/api/artifacts/diagnose?rootName=app&rootVersion=1&at=2026-07-01T00:00:00Z",
                JsonNode.class);
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(future.getBody().path("blocked")).hasSize(1);
        assertThat(future.getBody().path("resolvedAt").asText())
                .isEqualTo("2026-07-01T00:00:00Z");

        // 诊断不产生锁。
        ResponseEntity<JsonNode> locks = restTemplate.getForEntity(
                "/api/artifacts/locks", JsonNode.class);
        assertThat(locks.getBody()).hasSize(0);
    }

    @Test
    void batchIdempotentReplayReturnsSameSnapshot() {
        registerArtifact("lib", 1);
        registerArtifact("lib", 2);
        String rid = UUID.randomUUID().toString();
        String body = toJson(Map.of("windows", List.of(
                window("lib", 1, 2, "2026-06-01T00:00:00Z", "x"))));

        ResponseEntity<JsonNode> first = restTemplate.postForEntity(
                "/api/artifacts/deprecations",
                new HttpEntity<>(body, jsonHeaders(rid)), JsonNode.class);
        ResponseEntity<JsonNode> replay = restTemplate.postForEntity(
                "/api/artifacts/deprecations",
                new HttpEntity<>(body, jsonHeaders(rid)), JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("appliedAt").asText())
                .isEqualTo(first.getBody().path("appliedAt").asText());

        ResponseEntity<JsonNode> all = restTemplate.getForEntity(
                "/api/artifacts/deprecations", JsonNode.class);
        assertThat(all.getBody()).hasSize(1);
    }
}
