package com.example.starter.api;

import com.example.starter.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 弃用窗口能力的真实 HTTP 入口测试：状态码、JSON 结构、错误明细与幂等重放。
 * 使用固定可变时钟：待生效窗口取 T0 之后一小时，立即生效窗口取 T0（左闭）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeprecationControllerHttpTest {

    private static final Instant T0 = Instant.parse("2026-09-27T00:00:00Z");
    private static final String PENDING_AT = T0.plusSeconds(3600).toString();
    private static final String EFFECTIVE_NOW = T0.toString();
    private static final String PAST = T0.minusSeconds(3600).toString();

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.at(T0.toString());
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_deprecation_warning");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM deprecation_window");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
        clock.set(T0);
    }

    private static HttpHeaders jsonHeaders(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            headers.set("X-Request-Id", requestId);
        }
        return headers;
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private void register(String name, int version, Object... dependencies) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(dependencies));
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), jsonHeaders(requestId())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private Map<String, Object> dependency(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private Map<String, Object> window(String name, int version, String effectiveAt,
                                       int replacementVersion, String reason) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("name", name);
        window.put("version", version);
        window.put("effectiveAt", effectiveAt);
        window.put("replacementVersion", replacementVersion);
        if (reason != null) {
            window.put("reason", reason);
        }
        return window;
    }

    private ResponseEntity<JsonNode> postDeprecations(String requestId, List<Map<String, Object>> windows) {
        return restTemplate.postForEntity("/api/artifacts/deprecations",
                new HttpEntity<>(toJson(Map.of("windows", windows)), jsonHeaders(requestId)),
                JsonNode.class);
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void batchRegisterReturns201AndHistoryDetailDiagnosticsAreQueryable() {
        register("lib", 1);
        register("lib", 2);

        ResponseEntity<JsonNode> created = postDeprecations(requestId(),
                List.of(window("lib", 1, PENDING_AT, 2, "旧版下线")));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("repositoryVersion").asLong()).isEqualTo(3L);
        assertThat(created.getBody().path("windows")).hasSize(1);
        JsonNode window = created.getBody().path("windows").get(0);
        assertThat(window.path("name").asText()).isEqualTo("lib");
        assertThat(window.path("version").asInt()).isEqualTo(1);
        assertThat(window.path("replacementVersion").asInt()).isEqualTo(2);
        assertThat(window.path("reason").asText()).isEqualTo("旧版下线");
        assertThat(window.path("revoked").asBoolean()).isFalse();
        assertThat(window.path("revokedAt").isNull()).isTrue();
        assertThat(window.path("createdAt").asText()).isEqualTo(EFFECTIVE_NOW);

        ResponseEntity<JsonNode> history = restTemplate.getForEntity(
                "/api/artifacts/deprecations", JsonNode.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).hasSize(1);

        ResponseEntity<JsonNode> detail = restTemplate.getForEntity(
                "/api/artifacts/lib/versions/1/deprecation", JsonNode.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().path("effectiveAt").asText()).isEqualTo(PENDING_AT);

        ResponseEntity<JsonNode> diagnostics = restTemplate.getForEntity(
                "/api/artifacts/deprecations/diagnostics", JsonNode.class);
        assertThat(diagnostics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(diagnostics.getBody().path("now").asText()).isEqualTo(EFFECTIVE_NOW);
        assertThat(diagnostics.getBody().path("total").asInt()).isEqualTo(1);
        assertThat(diagnostics.getBody().path("pending").asInt()).isEqualTo(1);
        assertThat(diagnostics.getBody().path("effective").asInt()).isZero();
        assertThat(diagnostics.getBody().path("revoked").asInt()).isZero();

        ResponseEntity<JsonNode> missing = restTemplate.getForEntity(
                "/api/artifacts/lib/versions/2/deprecation", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lockBeforeEffectiveReturnsWarningsInResponse() {
        register("app", 1, dependency("lib", 1, 1));
        register("lib", 1);
        register("lib", 2);
        postDeprecations(requestId(), List.of(window("lib", 1, PENDING_AT, 2, "旧版下线")));

        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":4}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(requestId())), JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode warnings = lock.getBody().path("deprecationWarnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).path("name").asText()).isEqualTo("lib");
        assertThat(warnings.get(0).path("replacementVersion").asInt()).isEqualTo(2);
        assertThat(warnings.get(0).path("effectiveAt").asText()).isEqualTo(PENDING_AT);

        // 历史锁查询返回同样的固化警告。
        long lockId = lock.getBody().path("id").asLong();
        ResponseEntity<JsonNode> queried = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}", JsonNode.class, lockId);
        assertThat(queried.getBody().path("deprecationWarnings")).hasSize(1);
    }

    @Test
    void lockAfterEffectiveReturns422WithSortedDetails() {
        register("app", 1, dependency("lib", 1, 1));
        register("lib", 1);
        register("lib", 2);
        // 立即生效：生效时刻即当前时钟（左闭），锁定时刻不早于它。
        postDeprecations(requestId(), List.of(window("lib", 1, EFFECTIVE_NOW, 2, null)));

        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":4}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(requestId())), JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(lock.getBody().path("error").asText()).isEqualTo("UNPROCESSABLE_ENTITY");
        JsonNode details = lock.getBody().path("details");
        assertThat(details.isArray()).isTrue();
        assertThat(details).hasSize(1);
        assertThat(details.get(0).path("name").asText()).isEqualTo("lib");
        assertThat(details.get(0).path("version").asInt()).isEqualTo(1);
        assertThat(details.get(0).path("replacementVersion").asInt()).isEqualTo(2);
        assertThat(details.get(0).path("effectiveAt").asText()).isEqualTo(EFFECTIVE_NOW);

        ResponseEntity<JsonNode> locks = restTemplate.getForEntity(
                "/api/artifacts/locks", JsonNode.class);
        assertThat(locks.getBody()).hasSize(0);
    }

    @Test
    void revokeFlowAndValidationFailures() {
        register("lib", 1);
        register("lib", 2);
        postDeprecations(requestId(), List.of(window("lib", 1, PENDING_AT, 2, null)));

        String rid = requestId();
        ResponseEntity<JsonNode> revoked = restTemplate.exchange(
                "/api/artifacts/lib/versions/1/deprecation/revoke",
                HttpMethod.POST, new HttpEntity<>("", jsonHeaders(rid)), JsonNode.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().path("window").path("revoked").asBoolean()).isTrue();
        assertThat(revoked.getBody().path("window").path("revokedAt").asText())
                .isEqualTo(EFFECTIVE_NOW);
        assertThat(revoked.getBody().path("repositoryVersion").asLong()).isEqualTo(4L);

        // 撤销幂等重放：同 requestId 同参返回首次快照。
        ResponseEntity<JsonNode> replay = restTemplate.exchange(
                "/api/artifacts/lib/versions/1/deprecation/revoke",
                HttpMethod.POST, new HttpEntity<>("", jsonHeaders(rid)), JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("window").path("revokedAt").asText())
                .isEqualTo(revoked.getBody().path("window").path("revokedAt").asText());

        // 新 requestId 重复撤销 → 409；历史记录仍保留。
        ResponseEntity<JsonNode> again = restTemplate.exchange(
                "/api/artifacts/lib/versions/1/deprecation/revoke",
                HttpMethod.POST, new HttpEntity<>("", jsonHeaders(requestId())), JsonNode.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> history = restTemplate.getForEntity(
                "/api/artifacts/deprecations", JsonNode.class);
        assertThat(history.getBody()).hasSize(1);
        assertThat(history.getBody().get(0).path("revoked").asBoolean()).isTrue();
    }

    @Test
    void invalidDeprecationRequestsReturnDistinguishableErrors() {
        register("lib", 1);
        register("lib", 2);

        // 生效时刻早于当前时钟 → 422。
        ResponseEntity<JsonNode> past = postDeprecations(requestId(),
                List.of(window("lib", 1, PAST, 2, null)));
        assertThat(past.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 替代版本不存在 → 422，且整批不写入。
        ResponseEntity<JsonNode> missingReplacement = postDeprecations(requestId(),
                List.of(window("lib", 1, PENDING_AT, 9, null)));
        assertThat(missingReplacement.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM deprecation_window", Integer.class)).isZero();

        // 被弃用版本不存在 → 404。
        ResponseEntity<JsonNode> missingArtifact = postDeprecations(requestId(),
                List.of(window("ghost", 1, PENDING_AT, 2, null)));
        assertThat(missingArtifact.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 缺少 X-Request-Id → 400。
        ResponseEntity<JsonNode> noRequestId = restTemplate.postForEntity(
                "/api/artifacts/deprecations",
                new HttpEntity<>(toJson(Map.of("windows", List.of(
                        window("lib", 1, PENDING_AT, 2, null)))),
                        jsonHeaders(null)),
                JsonNode.class);
        assertThat(noRequestId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 空批量 → 400。
        ResponseEntity<JsonNode> empty = restTemplate.postForEntity("/api/artifacts/deprecations",
                new HttpEntity<>(toJson(Map.of("windows", List.of())), jsonHeaders(requestId())),
                JsonNode.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sameRequestIdReplaysBatchResponse() {
        register("lib", 1);
        register("lib", 2);
        String rid = requestId();
        List<Map<String, Object>> windows = List.of(window("lib", 1, PENDING_AT, 2, null));

        ResponseEntity<JsonNode> first = postDeprecations(rid, windows);
        ResponseEntity<JsonNode> replay = postDeprecations(rid, windows);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("windows").get(0).path("id").asLong())
                .isEqualTo(first.getBody().path("windows").get(0).path("id").asLong());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM deprecation_window", Integer.class)).isEqualTo(1);

        // 同 requestId 异参 → 409。
        ResponseEntity<JsonNode> conflict = postDeprecations(rid, List.of(
                window("lib", 1, T0.plusSeconds(7200).toString(), 2, null)));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
