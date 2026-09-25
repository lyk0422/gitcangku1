package com.example.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 来源证明 REST 入口测试：策略、证明、发布与诊断查询的 HTTP 状态码与 JSON 结构。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProvenanceControllerHttpTest {

    private static final String DIGEST_APP = "a".repeat(64);
    private static final String DIGEST_LIB = "b".repeat(64);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM publish_entry");
        jdbcTemplate.update("DELETE FROM publish_record");
        jdbcTemplate.update("DELETE FROM attestation");
        jdbcTemplate.update("DELETE FROM provenance_policy_repo");
        jdbcTemplate.update("DELETE FROM provenance_policy");
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

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void register(String name, int version, String digest, Object... dependencies) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(dependencies));
        if (digest != null) {
            body.put("digest", digest);
        }
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), jsonHeaders(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private Map<String, Object> dependency(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private ResponseEntity<JsonNode> createPolicy(int minLevel, String... repos) {
        Map<String, Object> body = Map.of("minLevel", minLevel, "allowedRepos", List.of(repos));
        return restTemplate.postForEntity("/api/provenance/policies",
                new HttpEntity<>(toJson(body), jsonHeaders(rid())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> attest(String name, int version, String repo,
                                            String digest, int level) {
        Map<String, Object> body = Map.of("name", name, "version", version,
                "repoId", repo, "digest", digest, "level", level);
        return restTemplate.postForEntity("/api/provenance/attestations",
                new HttpEntity<>(toJson(body), jsonHeaders(rid())), JsonNode.class);
    }

    @Test
    void policyLifecycleOverHttp() {
        ResponseEntity<JsonNode> created = createPolicy(2, "central", "backup");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("version").asInt()).isEqualTo(1);
        assertThat(created.getBody().path("allowedRepos").get(0).asText()).isEqualTo("backup");

        ResponseEntity<JsonNode> list = restTemplate.getForEntity(
                "/api/provenance/policies", JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);

        // 缺少 X-Request-Id → 400。
        ResponseEntity<JsonNode> missingHeader = restTemplate.postForEntity(
                "/api/provenance/policies",
                new HttpEntity<>(toJson(Map.of("minLevel", 1, "allowedRepos", List.of("central"))),
                        jsonHeaders(null)),
                JsonNode.class);
        assertThat(missingHeader.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lockGatePublishAndDiagnosticsOverHttp() {
        register("app", 1, DIGEST_APP, dependency("lib", 1, 1));
        register("lib", 1, DIGEST_LIB);
        assertThat(createPolicy(2, "central").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 缺证明：解析被门禁拦截，返回 422 与可区分错误码。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":2}";
        ResponseEntity<JsonNode> blocked = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(rid())), JsonNode.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blocked.getBody().path("error").asText()).isEqualTo("POLICY_VIOLATION");
        assertThat(blocked.getBody().path("message").asText())
                .contains("MISSING_ATTESTATION").contains("app:1>lib:1");

        // 补齐证明后锁定成功。
        assertThat(attest("app", 1, "central", DIGEST_APP, 2).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(attest("lib", 1, "central", DIGEST_LIB, 2).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> locked = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(rid())), JsonNode.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long lockId = locked.getBody().path("id").asLong();

        // 发布：201，同操作者重放返回同一指纹。
        ResponseEntity<JsonNode> published = restTemplate.postForEntity(
                "/api/artifacts/locks/" + lockId + "/publish",
                new HttpEntity<>(toJson(Map.of("operator", "op-1")), jsonHeaders(null)),
                JsonNode.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(published.getBody().path("policyVersion").asInt()).isEqualTo(1);
        String key = published.getBody().path("provenanceKey").asText();
        assertThat(key).hasSize(64);

        ResponseEntity<JsonNode> replay = restTemplate.postForEntity(
                "/api/artifacts/locks/" + lockId + "/publish",
                new HttpEntity<>(toJson(Map.of("operator", "op-1")), jsonHeaders(null)),
                JsonNode.class);
        assertThat(replay.getBody().path("provenanceKey").asText()).isEqualTo(key);
        assertThat(replay.getBody().path("id").asLong())
                .isEqualTo(published.getBody().path("id").asLong());

        // 来源路径查询：已发布返回冻结快照。
        ResponseEntity<JsonNode> provenance = restTemplate.getForEntity(
                "/api/artifacts/locks/" + lockId + "/provenance", JsonNode.class);
        assertThat(provenance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(provenance.getBody().path("published").asBoolean()).isTrue();
        assertThat(provenance.getBody().path("policyVersion").asInt()).isEqualTo(1);
        assertThat(provenance.getBody().path("entries")).hasSize(2);
        assertThat(provenance.getBody().path("entries").get(1).path("path").asText())
                .isEqualTo("app:1>lib:1");

        // 发布阻断诊断：已发布且无违规。
        ResponseEntity<JsonNode> diagnostic = restTemplate.getForEntity(
                "/api/artifacts/locks/" + lockId + "/publish-diagnostic", JsonNode.class);
        assertThat(diagnostic.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(diagnostic.getBody().path("published").asBoolean()).isTrue();
        assertThat(diagnostic.getBody().path("violations")).isEmpty();

        // 批量迁移预校验：候选策略更严格，列出违规但不写入。
        ResponseEntity<JsonNode> migration = restTemplate.postForEntity(
                "/api/provenance/policies/migrate-check",
                new HttpEntity<>(toJson(Map.of("minLevel", 9, "allowedRepos", List.of("other"))),
                        jsonHeaders(null)),
                JsonNode.class);
        assertThat(migration.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(migration.getBody().path("results")).hasSize(1);
        assertThat(migration.getBody().path("results").get(0).path("violations")).isNotEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM provenance_policy", Integer.class)).isEqualTo(1);
    }

    @Test
    void revokeOverHttpBlocksUnpublishedLock() {
        register("app", 1, DIGEST_APP, dependency("lib", 1, 1));
        register("lib", 1, DIGEST_LIB);
        createPolicy(1, "central");
        attest("app", 1, "central", DIGEST_APP, 1);
        attest("lib", 1, "central", DIGEST_LIB, 1);
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":2}";
        ResponseEntity<JsonNode> locked = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(rid())), JsonNode.class);
        long lockId = locked.getBody().path("id").asLong();

        ResponseEntity<JsonNode> revoked = restTemplate.postForEntity(
                "/api/provenance/attestations/lib/versions/1/revoke",
                new HttpEntity<>("", jsonHeaders(rid())), JsonNode.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().path("revoked").asBoolean()).isTrue();

        ResponseEntity<JsonNode> publish = restTemplate.postForEntity(
                "/api/artifacts/locks/" + lockId + "/publish",
                new HttpEntity<>(toJson(Map.of("operator", "op-1")), jsonHeaders(null)),
                JsonNode.class);
        assertThat(publish.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(publish.getBody().path("message").asText()).contains("ATTESTATION_REVOKED");
    }
}
