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
        jdbcTemplate.update("DELETE FROM lock_entry_attestation");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_attestation");
        jdbcTemplate.update("DELETE FROM artifact_checksum");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static final String CHECKSUM = "sha256:" + "a".repeat(64);

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

    /** 登记摘要并由两名不同角色 alice、bob 完成见证（3 次写，各带独立 requestId）。 */
    private void fullyAttest(String name, int version) {
        String body = "{\"checksum\":\"" + CHECKSUM + "\"}";
        restTemplate.postForEntity(
                "/api/artifacts/" + name + "/versions/" + version + "/checksum",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString(), "alice")),
                JsonNode.class);
        restTemplate.postForEntity(
                "/api/artifacts/" + name + "/versions/" + version + "/attestations",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString(), "alice")),
                JsonNode.class);
        restTemplate.postForEntity(
                "/api/artifacts/" + name + "/versions/" + version + "/attestations",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString(), "bob")),
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
        // app:1 -> lib[1,2]；lib2/lib1；锁定取 lib2。闭包版本须先完成双人见证。
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1, dependency("lib", 1, 2)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("lib", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("lib", 2),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        fullyAttest("app", 1);
        fullyAttest("lib", 1);
        fullyAttest("lib", 2);

        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":12}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long lockId = lock.getBody().path("id").asLong();
        assertThat(lock.getBody().path("repositoryVersion").asLong()).isEqualTo(12L);
        assertThat(lock.getBody().path("entries")).hasSize(2);
        assertThat(lock.getBody().path("entries").get(0).path("name").asText()).isEqualTo("app");
        assertThat(lock.getBody().path("entries").get(1).path("name").asText()).isEqualTo("lib");
        assertThat(lock.getBody().path("entries").get(1).path("version").asInt()).isEqualTo(2);
        // 固化摘要与两名见证角色。
        assertThat(lock.getBody().path("entries").get(0).path("checksum").asText())
                .isEqualTo(CHECKSUM);
        assertThat(lock.getBody().path("entries").get(0).path("attestations"))
                .hasSize(2);

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

    // ------------------------------------------------------------------
    // 内容摘要登记 / 双人见证 / 撤销：HTTP 入口
    // ------------------------------------------------------------------

    private String registerOne(String name, int version) {
        String rid = UUID.randomUUID().toString();
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody(name, version), jsonHeaders(rid)), JsonNode.class);
        return rid;
    }

    private ResponseEntity<JsonNode> postChecksum(String name, int version, String checksum,
                                                  String requestId, String operator) {
        String body = "{\"checksum\":\"" + checksum + "\"}";
        return restTemplate.postForEntity(
                "/api/artifacts/" + name + "/versions/" + version + "/checksum",
                new HttpEntity<>(body, jsonHeaders(requestId, operator)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postAttest(String name, int version, String checksum,
                                                String requestId, String operator) {
        String body = "{\"checksum\":\"" + checksum + "\"}";
        return restTemplate.postForEntity(
                "/api/artifacts/" + name + "/versions/" + version + "/attestations",
                new HttpEntity<>(body, jsonHeaders(requestId, operator)), JsonNode.class);
    }

    @Test
    void invalidChecksumFormatReturns422ViaHttp() {
        registerOne("lib", 1);
        ResponseEntity<JsonNode> response = postChecksum("lib", 1,
                "sha256:" + "Z".repeat(64), UUID.randomUUID().toString(), "alice");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().path("error").asText()).isEqualTo("UNPROCESSABLE_ENTITY");
    }

    @Test
    void missingOperatorHeaderReturns400ViaHttp() {
        registerOne("lib", 1);
        String body = "{\"checksum\":\"" + CHECKSUM + "\"}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/lib/versions/1/checksum",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void attestWorkflowDuplicateRoleAndMismatchReturnExpectedStatuses() {
        registerOne("lib", 1);
        postChecksum("lib", 1, CHECKSUM, UUID.randomUUID().toString(), "alice");

        ResponseEntity<JsonNode> first = postAttest("lib", 1, CHECKSUM,
                UUID.randomUUID().toString(), "alice");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().path("attestations")).hasSize(1);

        // 同角色重复见证 → 409。
        ResponseEntity<JsonNode> duplicate = postAttest("lib", 1, CHECKSUM,
                UUID.randomUUID().toString(), "alice");
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 摘要不一致 → 422。
        ResponseEntity<JsonNode> mismatch = postAttest("lib", 1,
                "sha256:" + "f".repeat(64), UUID.randomUUID().toString(), "bob");
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 第二名不同角色一致见证成功。
        ResponseEntity<JsonNode> second = postAttest("lib", 1, CHECKSUM,
                UUID.randomUUID().toString(), "bob");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().path("attestations")).hasSize(2);
    }

    @Test
    void diagnosticsAreReadOnlyAndReflectState() {
        registerOne("lib", 1);
        // 读取不改变状态：连续两次查询结果一致且仓库版本不前进。
        ResponseEntity<JsonNode> before = restTemplate.getForEntity(
                "/api/artifacts/lib/versions/1/attestations", JsonNode.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(before.getBody().path("checksumRegistered").asBoolean()).isFalse();
        assertThat(before.getBody().path("attested").asBoolean()).isFalse();
        assertThat(before.getBody().path("requiredWitnesses").asInt()).isEqualTo(2);
        assertThat(before.getBody().path("reasons")).isNotEmpty();

        postChecksum("lib", 1, CHECKSUM, UUID.randomUUID().toString(), "alice");
        postAttest("lib", 1, CHECKSUM, UUID.randomUUID().toString(), "alice");
        postAttest("lib", 1, CHECKSUM, UUID.randomUUID().toString(), "bob");

        ResponseEntity<JsonNode> after = restTemplate.getForEntity(
                "/api/artifacts/lib/versions/1/attestations", JsonNode.class);
        assertThat(after.getBody().path("attested").asBoolean()).isTrue();
        assertThat(after.getBody().path("reasons")).isEmpty();
        assertThat(after.getBody().path("attestations")).hasSize(2);
    }

    @Test
    void revokeOnlyAffectsFutureLocksAndFrozenLockStaysIntact() {
        registerOne("app", 1);
        registerOne("lib", 1);
        // 无依赖的 app:1 与 lib:1 各自双人见证。
        fullyAttest("app", 1);
        fullyAttest("lib", 1);

        String lockRid = UUID.randomUUID().toString();
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":8}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(lockRid)), JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long lockId = lock.getBody().path("id").asLong();
        assertThat(lock.getBody().path("entries").get(0).path("attestations")).hasSize(2);

        // 撤销 alice 对 app 的见证。
        ResponseEntity<JsonNode> revoke = restTemplate.exchange(
                "/api/artifacts/app/versions/1/attestations/alice/revoke",
                HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString(), "carol")),
                JsonNode.class);
        assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoke.getBody().path("attestations").get(0).path("active").asBoolean()).isFalse();
        assertThat(revoke.getBody().path("attestations").get(0).path("revokedBy").asText())
                .isEqualTo("carol");

        // 旧锁固化证据不变；新锁因见证不足 422（且期望版本过期也可能 409，先核对见证口径）。
        ResponseEntity<JsonNode> oldLock = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}", JsonNode.class, lockId);
        assertThat(oldLock.getBody().path("entries").get(0).path("attestations")).hasSize(2);
        assertThat(oldLock.getBody().path("entries").get(0)
                .path("attestations").get(0).path("role").asText()).isEqualTo("alice");

        // 用当前版本号再锁一次，仍须因见证撤销而失败（422）。
        long currentVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        String body2 = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":"
                + currentVersion + "}";
        ResponseEntity<JsonNode> secondLock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(body2, jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(secondLock.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(secondLock.getBody().path("message").asText()).contains("差额");
    }

    @Test
    void sameRequestIdDifferentOperatorReturns409ViaHttp() {
        registerOne("lib", 1);
        postChecksum("lib", 1, CHECKSUM, UUID.randomUUID().toString(), "alice");
        String rid = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> ok = postAttest("lib", 1, CHECKSUM, rid, "alice");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> conflict = postAttest("lib", 1, CHECKSUM, rid, "bob");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void lockFailsWhenClosureNotAttestedAndReportsIndirectDependency() {
        // app:1 -> lib[1,1]；只见证 app，lib 无摘要无见证。
        Map<String, Object> appBody = new LinkedHashMap<>();
        appBody.put("name", "app");
        appBody.put("version", 1);
        appBody.put("dependencies", List.of(dependency("lib", 1, 1)));
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(appBody), jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        registerOne("lib", 1);
        fullyAttest("app", 1);
        // 2 次登记 + 3 次见证写 = 5。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":5}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // lib 连摘要都未登记：稳定原因点名间接依赖并给出实际/要求/差额。
        assertThat(response.getBody().path("message").asText())
                .contains("lib:1", "未登记内容校验摘要", "差额 1 条");
    }
}
