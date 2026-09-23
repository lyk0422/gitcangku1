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
 * 签名信任策略、补签、钥匙撤销与锁图证据的真实 HTTP 入口测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignaturePolicyControllerHttpTest {

    private static final String DIGEST = "a".repeat(64);
    private static final String LIB_DIGEST = "b".repeat(64);

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
        jdbcTemplate.update("DELETE FROM signing_policy_key");
        jdbcTemplate.update("DELETE FROM signing_policy");
        jdbcTemplate.update("DELETE FROM signing_key");
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

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String registerArtifact(String name, int version, String digest,
                                    Object... dependencies) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("contentDigest", digest);
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

    private String policyBody(long policyVersion, int threshold, String... keyIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policyVersion", policyVersion);
        body.put("keyIds", List.of(keyIds));
        body.put("threshold", threshold);
        body.put("effectiveAt", Instant.now().minusSeconds(60).toString());
        return toJson(body);
    }

    private String signatureBody(String keyId, String digest) {
        return toJson(Map.of("keyId", keyId, "digest", digest));
    }

    @Test
    void fullSignaturePolicyLockWorkflowOverHttp() {
        // app:1 -> lib[1,1]，显式内容摘要。
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerArtifact("app", 1, DIGEST, dependency("lib", 1, 1)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerArtifact("lib", 1, LIB_DIGEST),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        // 发布策略 m=2，钥匙 k1/k2。
        ResponseEntity<JsonNode> policy = restTemplate.postForEntity("/api/artifacts/policies",
                new HttpEntity<>(policyBody(1L, 2, "k1", "k2"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(policy.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(policy.getBody().path("keyIds")).hasSize(2);

        // 当前生效策略可只读查询。
        ResponseEntity<JsonNode> effective = restTemplate.getForEntity(
                "/api/artifacts/policies/effective", JsonNode.class);
        assertThat(effective.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(effective.getBody().path("policyVersion").asLong()).isEqualTo(1L);

        // 每个节点两把钥匙补签。
        for (String key : new String[]{"k1", "k2"}) {
            assertThat(restTemplate.postForEntity("/api/artifacts/app/versions/1/signatures",
                    new HttpEntity<>(signatureBody(key, DIGEST),
                            jsonHeaders(UUID.randomUUID().toString())), JsonNode.class)
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(restTemplate.postForEntity("/api/artifacts/lib/versions/1/signatures",
                    new HttpEntity<>(signatureBody(key, LIB_DIGEST),
                            jsonHeaders(UUID.randomUUID().toString())), JsonNode.class)
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        // 签名证据只读查询。
        ResponseEntity<JsonNode> signatures = restTemplate.getForEntity(
                "/api/artifacts/app/versions/1/signatures", JsonNode.class);
        assertThat(signatures.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signatures.getBody().isArray()).isTrue();
        assertThat(signatures.getBody()).hasSize(2);

        // 锁定：当前仓库版本为 2(制品)+1(策略)+4(补签)=7。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":7}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode entries = lock.getBody().path("entries");
        assertThat(entries).hasSize(2);
        JsonNode appEntry = entries.get(0);
        assertThat(appEntry.path("name").asText()).isEqualTo("app");
        assertThat(appEntry.path("digest").asText()).isEqualTo(DIGEST);
        assertThat(appEntry.path("policyVersion").asLong()).isEqualTo(1L);
        assertThat(appEntry.path("signerKeyIds")).hasSize(2);
    }

    @Test
    void lockWithInsufficientSignaturesReturns422OverHttp() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerArtifact("app", 1, DIGEST),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts/policies",
                new HttpEntity<>(policyBody(1L, 2, "k1", "k2"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts/app/versions/1/signatures",
                new HttpEntity<>(signatureBody("k1", DIGEST),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        // 仓库版本 = 1(制品)+1(策略)+1(补签)=3，仅 1 个有效签名，不足 m=2。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":3}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().path("error").asText()).isEqualTo("UNPROCESSABLE_ENTITY");
    }

    @Test
    void signatureDigestMismatchReturns422OverHttp() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerArtifact("app", 1, DIGEST),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts/policies",
                new HttpEntity<>(policyBody(1L, 1, "k1"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/signatures",
                new HttpEntity<>(signatureBody("k1", "f".repeat(64)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void invalidPolicyThresholdReturns400OverHttp() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/policies",
                new HttpEntity<>(policyBody(1L, 2, "k1"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void revokeKeyWorkflowLocksOutNewSignaturesOverHttp() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerArtifact("app", 1, DIGEST),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts/policies",
                new HttpEntity<>(policyBody(1L, 1, "k1"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        String revokeRid = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> revoked = restTemplate.exchange(
                "/api/artifacts/keys/k1/revoke",
                HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(revokeRid)),
                JsonNode.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().path("revoked").asBoolean()).isTrue();

        // 撤销后补签 -> 409。
        ResponseEntity<JsonNode> sign = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/signatures",
                new HttpEntity<>(signatureBody("k1", DIGEST),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(sign.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 钥匙状态只读查询反映撤销。
        ResponseEntity<JsonNode> key = restTemplate.getForEntity(
                "/api/artifacts/keys/k1", JsonNode.class);
        assertThat(key.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(key.getBody().path("revoked").asBoolean()).isTrue();
    }

    @Test
    void readOnlyEvidenceQueriesReturn404WhenMissingOverHttp() {
        assertThat(restTemplate.getForEntity("/api/artifacts/policies/999", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/api/artifacts/keys/ghost", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity(
                "/api/artifacts/ghost/versions/1/signatures", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
