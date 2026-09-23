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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签名信任策略、补签、撤销与锁图证据的真实 HTTP 入口测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignatureControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_signature");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("DELETE FROM policy_key");
        jdbcTemplate.update("DELETE FROM signature_policy");
        jdbcTemplate.update("DELETE FROM trusted_key");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static HttpHeaders headers(String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        return h;
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String register(String name, int version, Object... deps) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(deps));
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts", new HttpEntity<>(toJson(body), headers(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("contentDigest").asText();
    }

    private Map<String, Object> dependency(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private void publishPolicy(long policyVersion, int threshold, String... keyIds) {
        Map<String, Object> body = Map.of(
                "policyVersion", policyVersion,
                "keyIds", List.of(keyIds),
                "thresholdM", threshold,
                "effectiveAt", Instant.now().minusSeconds(60).toString());
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/policies", new HttpEntity<>(toJson(body), headers(rid())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void sign(String name, int version, String keyId, String digest) {
        Map<String, Object> body = Map.of("keyId", keyId, "digest", digest);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/" + name + "/versions/" + version + "/signatures",
                new HttpEntity<>(toJson(body), headers(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void fullSignatureWorkflowFreezesEvidenceAndServesQueries() {
        String appDigest = register("app", 1, dependency("lib", 1, 1));
        String libDigest = register("lib", 1);
        publishPolicy(1, 2, "k1", "k2");
        sign("app", 1, "k1", appDigest);
        sign("app", 1, "k2", appDigest);
        sign("lib", 1, "k1", libDigest);
        sign("lib", 1, "k2", libDigest);

        long repositoryVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        Map<String, Object> lockBody = Map.of(
                "rootName", "app", "rootVersion", 1,
                "expectedRepositoryVersion", repositoryVersion);
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity(
                "/api/artifacts/locks", new HttpEntity<>(toJson(lockBody), headers(rid())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode entries = lock.getBody().path("entries");
        assertThat(entries).hasSize(2);
        for (JsonNode entry : entries) {
            assertThat(entry.path("policyVersion").asLong()).isEqualTo(1L);
            assertThat(entry.path("signatureKeyIds")).hasSize(2);
            assertThat(entry.path("contentDigest").asText()).hasSize(64);
        }

        // 只读证据查询。
        ResponseEntity<JsonNode> policies = restTemplate.getForEntity(
                "/api/artifacts/policies", JsonNode.class);
        assertThat(policies.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(policies.getBody()).hasSize(1);

        ResponseEntity<JsonNode> keys = restTemplate.getForEntity(
                "/api/artifacts/keys", JsonNode.class);
        assertThat(keys.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(keys.getBody()).hasSize(2);

        ResponseEntity<JsonNode> signatures = restTemplate.getForEntity(
                "/api/artifacts/app/versions/1/signatures", JsonNode.class);
        assertThat(signatures.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signatures.getBody()).hasSize(2);
    }

    @Test
    void lockWithoutEnoughSignaturesReturns422() {
        register("app", 1);
        publishPolicy(1, 2, "k1", "k2");
        // 不补签直接锁：阈值不足。
        long repositoryVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        Map<String, Object> lockBody = Map.of(
                "rootName", "app", "rootVersion", 1,
                "expectedRepositoryVersion", repositoryVersion);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/locks", new HttpEntity<>(toJson(lockBody), headers(rid())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void revokeKeyKeepsHistoryButNewLockIgnoresIt() {
        String digest = register("app", 1);
        publishPolicy(1, 2, "k1", "k2");
        sign("app", 1, "k1", digest);

        ResponseEntity<JsonNode> revoked = restTemplate.exchange(
                "/api/artifacts/keys/k2/revoke", HttpMethod.POST,
                new HttpEntity<>("", headers(rid())), JsonNode.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().path("revoked").asBoolean()).isTrue();

        // 撤销后补签仍可追加（历史签名不改写），但新锁定忽略 k2，阈值不足 → 422。
        Map<String, Object> body = Map.of("keyId", "k2", "digest", digest);
        ResponseEntity<JsonNode> signAgain = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/signatures",
                new HttpEntity<>(toJson(body), headers(rid())), JsonNode.class);
        assertThat(signAgain.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        long repositoryVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        Map<String, Object> lockBody = Map.of(
                "rootName", "app", "rootVersion", 1,
                "expectedRepositoryVersion", repositoryVersion);
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity(
                "/api/artifacts/locks", new HttpEntity<>(toJson(lockBody), headers(rid())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void invalidPolicyThresholdReturns400() {
        Map<String, Object> body = Map.of(
                "policyVersion", 1,
                "keyIds", List.of("k1"),
                "thresholdM", 2,
                "effectiveAt", Instant.now().toString());
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/policies", new HttpEntity<>(toJson(body), headers(rid())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
