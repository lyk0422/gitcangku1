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
 * 真实 HTTP 入口测试：状态码、X-Request-Id 头、JSON 结构与幂等重放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArtifactControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_attestation");
        jdbcTemplate.update("DELETE FROM artifact_checksum");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static HttpHeaders jsonHeaders(String requestId) {
        return jsonHeaders(requestId, null);
    }

    private static HttpHeaders jsonHeaders(String requestId, String operator) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            headers.set("X-Request-Id", requestId);
        }
        if (operator != null) {
            headers.set("X-Operator", operator);
        }
        return headers;
    }

    private long repoVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    /** 通过 HTTP 完成摘要登记与两名不同发布角色的见证。 */
    private void attestFullyViaHttp(String name, int version, String digest) {
        String checksumBody = "{\"algorithm\":\"SHA-256\",\"digest\":\"" + digest + "\"}";
        ResponseEntity<JsonNode> checksum = restTemplate.postForEntity(
                "/api/artifacts/{name}/versions/{version}/checksum",
                new HttpEntity<>(checksumBody,
                        jsonHeaders(UUID.randomUUID().toString(), "rel-lead")),
                JsonNode.class, name, version);
        assertThat(checksum.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (String operator : List.of("ops-a", "ops-b")) {
            ResponseEntity<JsonNode> attestation = restTemplate.postForEntity(
                    "/api/artifacts/{name}/versions/{version}/attestations",
                    new HttpEntity<>("{\"digest\":\"" + digest + "\"}",
                            jsonHeaders(UUID.randomUUID().toString(), operator)),
                    JsonNode.class, name, version);
            assertThat(attestation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
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

    @Test
    void registerViaHttpReturns201AndReplaysWithSameRequestId() {
        String rid = UUID.randomUUID().toString();
        String body = registerBody("app", 1, dependency("lib", 1, 2));
        HttpEntity<String> request = new HttpEntity<>(body, jsonHeaders(rid));

        ResponseEntity<JsonNode> first = restTemplate.postForEntity(
                "/api/artifacts", request, JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().path("name").asText()).isEqualTo("app");
        assertThat(first.getBody().path("version").asInt()).isEqualTo(1);
        assertThat(first.getBody().path("repositoryVersion").asLong()).isEqualTo(1L);

        ResponseEntity<JsonNode> replay = restTemplate.postForEntity(
                "/api/artifacts", request, JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("createdAt").asText())
                .isEqualTo(first.getBody().path("createdAt").asText());
        assertThat(replay.getBody().path("repositoryVersion").asLong()).isEqualTo(1L);
    }

    @Test
    void sameRequestIdDifferentPayloadReturns409() {
        String rid = UUID.randomUUID().toString();
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1), jsonHeaders(rid)), JsonNode.class);

        ResponseEntity<JsonNode> conflict = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 2), jsonHeaders(rid)), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().path("error").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void missingRequestIdHeaderReturns400() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1), jsonHeaders(null)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidBeanReturns400() {
        // version 必须为正整数。
        Map<String, Object> body = Map.of("name", "app", "version", 0, "dependencies", List.of());
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void fullLockWorkflowAndHistoryQueries() {
        // app:1 -> lib[1,2]；lib2/lib1；锁定取 lib2。
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1, dependency("lib", 1, 2)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("lib", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("lib", 2),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        // 闭包版本 app:1、lib:2 完成摘要登记与双人见证。
        attestFullyViaHttp("app", 1, "a".repeat(64));
        attestFullyViaHttp("lib", 2, "b".repeat(64));

        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":"
                + repoVersion() + "}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long lockId = lock.getBody().path("id").asLong();
        assertThat(lock.getBody().path("repositoryVersion").asLong()).isEqualTo(repoVersion());
        assertThat(lock.getBody().path("entries")).hasSize(2);
        assertThat(lock.getBody().path("entries").get(0).path("name").asText()).isEqualTo("app");
        assertThat(lock.getBody().path("entries").get(1).path("name").asText()).isEqualTo("lib");
        assertThat(lock.getBody().path("entries").get(1).path("version").asInt()).isEqualTo(2);
        // 锁条目固化摘要与双人见证。
        assertThat(lock.getBody().path("entries").get(0).path("checksumDigest").asText())
                .isEqualTo("a".repeat(64));
        assertThat(lock.getBody().path("entries").get(0).path("attestationCount").asInt())
                .isEqualTo(2);
        assertThat(lock.getBody().path("entries").get(0).path("attesters")).hasSize(2);

        ResponseEntity<JsonNode> list = restTemplate.getForEntity(
                "/api/artifacts/locks", JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().isArray()).isTrue();
        assertThat(list.getBody()).hasSize(1);

        ResponseEntity<JsonNode> one = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}", JsonNode.class, lockId);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody().path("id").asLong()).isEqualTo(lockId);

        ResponseEntity<JsonNode> missing = restTemplate.getForEntity(
                "/api/artifacts/locks/9999", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void staleRepositoryVersionReturns409() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":0}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void infeasibleLockReturns422() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1, dependency("lib", 2, 2)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("lib", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":2}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void checksumAndAttestationHttpWorkflow() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        // 缺少 X-Operator 头 → 400。
        ResponseEntity<JsonNode> noOperator = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/checksum",
                new HttpEntity<>("{\"algorithm\":\"SHA-256\",\"digest\":\"" + "a".repeat(64) + "\"}",
                        jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(noOperator.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 非法摘要格式 → 422。
        ResponseEntity<JsonNode> badDigest = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/checksum",
                new HttpEntity<>("{\"algorithm\":\"SHA-256\",\"digest\":\"xyz\"}",
                        jsonHeaders(UUID.randomUUID().toString(), "rel-lead")),
                JsonNode.class);
        assertThat(badDigest.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        attestFullyViaHttp("app", 1, "a".repeat(64));

        // 明细查询：双人见证就绪。
        ResponseEntity<JsonNode> detail = restTemplate.getForEntity(
                "/api/artifacts/app/versions/1/checksum", JsonNode.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().path("digest").asText()).isEqualTo("a".repeat(64));
        assertThat(detail.getBody().path("activeAttestations").asInt()).isEqualTo(2);
        assertThat(detail.getBody().path("missingAttestations").asInt()).isEqualTo(0);
        assertThat(detail.getBody().path("attestations")).hasSize(2);

        // 诊断查询：单版本闭包就绪。
        ResponseEntity<JsonNode> diagnostics = restTemplate.getForEntity(
                "/api/artifacts/lock-diagnostics?rootName=app&rootVersion=1", JsonNode.class);
        assertThat(diagnostics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(diagnostics.getBody().path("resolvable").asBoolean()).isTrue();
        assertThat(diagnostics.getBody().path("ready").asBoolean()).isTrue();

        // 同角色重复见证 → 409。
        ResponseEntity<JsonNode> duplicate = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/attestations",
                new HttpEntity<>("{\"digest\":\"" + "a".repeat(64) + "\"}",
                        jsonHeaders(UUID.randomUUID().toString(), "ops-a")),
                JsonNode.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 撤销见证后诊断不再就绪；撤销不影响已生成的查询结构。
        ResponseEntity<JsonNode> revoke = restTemplate.exchange(
                "/api/artifacts/app/versions/1/attestations/ops-b/revoke",
                HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString(), "sec-admin")),
                JsonNode.class);
        assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoke.getBody().path("revoked").asBoolean()).isTrue();
        assertThat(revoke.getBody().path("activeAttestations").asInt()).isEqualTo(1);

        ResponseEntity<JsonNode> notReady = restTemplate.getForEntity(
                "/api/artifacts/lock-diagnostics?rootName=app&rootVersion=1", JsonNode.class);
        assertThat(notReady.getBody().path("ready").asBoolean()).isFalse();
    }

    @Test
    void withdrawWorkflowAndRepositoryVersionAdvances() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        String rid = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> withdrawn = restTemplate.exchange(
                "/api/artifacts/app/versions/1/withdraw",
                HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(rid)),
                JsonNode.class);
        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(withdrawn.getBody().path("withdrawn").asBoolean()).isTrue();
        assertThat(withdrawn.getBody().path("repositoryVersion").asLong()).isEqualTo(2L);

        // 撤回后锁定撤回的根 → 409。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":2}";
        ResponseEntity<JsonNode> lockOnWithdrawn = restTemplate.postForEntity(
                "/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lockOnWithdrawn.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
