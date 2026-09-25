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
        jdbcTemplate.update("DELETE FROM artifact_license");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM license_policy_allowed");
        jdbcTemplate.update("DELETE FROM license_policy");
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

        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":3}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long lockId = lock.getBody().path("id").asLong();
        assertThat(lock.getBody().path("repositoryVersion").asLong()).isEqualTo(3L);
        assertThat(lock.getBody().path("entries")).hasSize(2);
        assertThat(lock.getBody().path("entries").get(0).path("name").asText()).isEqualTo("app");
        assertThat(lock.getBody().path("entries").get(1).path("name").asText()).isEqualTo("lib");
        assertThat(lock.getBody().path("entries").get(1).path("version").asInt()).isEqualTo(2);

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
    // 许可证策略与锁定准入
    // ------------------------------------------------------------------

    @Test
    void licenseAndPolicyWorkflowOverHttp() {
        // app:1 -> lib[1,1]；lib:1 登记 GPL-3.0；策略仅允许 MIT。
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1, dependency("lib", 1, 1)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("lib", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        ResponseEntity<JsonNode> license = restTemplate.exchange(
                "/api/artifacts/lib/versions/1/license", HttpMethod.PUT,
                new HttpEntity<>("{\"license\":\"GPL-3.0\"}",
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(license.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(license.getBody().path("license").asText()).isEqualTo("GPL-3.0");
        assertThat(license.getBody().path("repositoryVersion").asLong()).isEqualTo(3L);

        ResponseEntity<JsonNode> policy = restTemplate.exchange(
                "/api/artifacts/policies/app", HttpMethod.PUT,
                new HttpEntity<>("{\"expectedVersion\":0,\"allowedLicenses\":[\"MIT\"],"
                                + "\"rejectUnknown\":false}",
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(policy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(policy.getBody().path("version").asLong()).isEqualTo(1L);

        ResponseEntity<JsonNode> policyView = restTemplate.getForEntity(
                "/api/artifacts/policies/app", JsonNode.class);
        assertThat(policyView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(policyView.getBody().path("allowedLicenses").get(0).asText()).isEqualTo("MIT");

        // 诊断查询：报告 lib 违规但不产生锁文件。
        ResponseEntity<JsonNode> diagnosis = restTemplate.postForEntity(
                "/api/artifacts/locks/diagnose",
                new HttpEntity<>("{\"rootName\":\"app\",\"rootVersion\":1}",
                        jsonHeaders(null)), JsonNode.class);
        assertThat(diagnosis.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(diagnosis.getBody().path("feasible").asBoolean()).isTrue();
        assertThat(diagnosis.getBody().path("violations")).hasSize(1);
        assertThat(diagnosis.getBody().path("violations").get(0).path("name").asText())
                .isEqualTo("lib");
        assertThat(diagnosis.getBody().path("violations").get(0).path("reason").asText())
                .isEqualTo("LICENSE_NOT_ALLOWED");

        // 锁定被策略拒绝：422 且响应携带稳定排序的违规列表。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":4}";
        ResponseEntity<JsonNode> rejected = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody().path("error").asText()).isEqualTo("LICENSE_POLICY_VIOLATION");
        assertThat(rejected.getBody().path("violations")).hasSize(1);
        assertThat(rejected.getBody().path("violations").get(0).path("license").asText())
                .isEqualTo("GPL-3.0");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();

        // 修订许可证后锁定成功，锁文件固化许可证与策略版本。
        restTemplate.exchange("/api/artifacts/lib/versions/1/license", HttpMethod.PUT,
                new HttpEntity<>("{\"license\":\"MIT\"}",
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        String lockBody2 = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":5}";
        ResponseEntity<JsonNode> locked = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody2, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(locked.getBody().path("policyVersion").asLong()).isEqualTo(1L);
        long lockId = locked.getBody().path("id").asLong();

        ResponseEntity<JsonNode> snapshot = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}/licenses", JsonNode.class, lockId);
        assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshot.getBody().path("policyVersion").asLong()).isEqualTo(1L);
        assertThat(snapshot.getBody().path("entries")).hasSize(2);
        assertThat(snapshot.getBody().path("entries").get(0).path("license").asText())
                .isEqualTo("UNKNOWN");
        assertThat(snapshot.getBody().path("entries").get(1).path("license").asText())
                .isEqualTo("MIT");

        // 历史锁定稳定性：再次修订许可证不改写快照。
        restTemplate.exchange("/api/artifacts/lib/versions/1/license", HttpMethod.PUT,
                new HttpEntity<>("{\"license\":\"BSD-3\"}",
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        ResponseEntity<JsonNode> snapshotAfter = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}/licenses", JsonNode.class, lockId);
        assertThat(snapshotAfter.getBody().path("entries").get(1).path("license").asText())
                .isEqualTo("MIT");
    }

    @Test
    void setLicenseOnWithdrawnVersionReturns409OverHttp() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.exchange("/api/artifacts/app/versions/1/withdraw", HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/artifacts/app/versions/1/license", HttpMethod.PUT,
                new HttpEntity<>("{\"license\":\"MIT\"}",
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void policyVersionConflictReturns409OverHttp() {
        restTemplate.exchange("/api/artifacts/policies/app", HttpMethod.PUT,
                new HttpEntity<>("{\"expectedVersion\":0,\"allowedLicenses\":[\"MIT\"],"
                                + "\"rejectUnknown\":false}",
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        ResponseEntity<JsonNode> conflict = restTemplate.exchange(
                "/api/artifacts/policies/app", HttpMethod.PUT,
                new HttpEntity<>("{\"expectedVersion\":0,\"allowedLicenses\":[\"BSD-3\"],"
                                + "\"rejectUnknown\":true}",
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<JsonNode> missing = restTemplate.getForEntity(
                "/api/artifacts/policies/ghost", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void licenseAndPolicyWritesAreIdempotentOverHttp() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        String licenseRid = UUID.randomUUID().toString();
        HttpEntity<String> licenseRequest = new HttpEntity<>("{\"license\":\"MIT\"}",
                jsonHeaders(licenseRid));
        ResponseEntity<JsonNode> first = restTemplate.exchange(
                "/api/artifacts/app/versions/1/license", HttpMethod.PUT, licenseRequest, JsonNode.class);
        ResponseEntity<JsonNode> replay = restTemplate.exchange(
                "/api/artifacts/app/versions/1/license", HttpMethod.PUT, licenseRequest, JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("updatedAt").asText())
                .isEqualTo(first.getBody().path("updatedAt").asText());
        assertThat(replay.getBody().path("repositoryVersion").asLong()).isEqualTo(2L);

        // 同键异参 → 409。
        ResponseEntity<JsonNode> conflict = restTemplate.exchange(
                "/api/artifacts/app/versions/1/license", HttpMethod.PUT,
                new HttpEntity<>("{\"license\":\"BSD-3\"}", jsonHeaders(licenseRid)), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
