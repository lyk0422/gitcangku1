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
 * 来源策略 REST 入口真实 HTTP 测试：状态码、请求头、JSON 结构、
 * 422 违规明细（可区分原因 + 完整路径）与发布重放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProvenanceControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM release_snapshot_entry");
        jdbcTemplate.update("DELETE FROM release_snapshot");
        jdbcTemplate.update("DELETE FROM provenance_attestation");
        jdbcTemplate.update("DELETE FROM provenance_policy_coordinate");
        jdbcTemplate.update("DELETE FROM provenance_policy");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private HttpHeaders headers(String operator) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Request-Id", uuid());
        if (operator != null) {
            h.set("X-Operator", operator);
        }
        return h;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> dependency(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private void registerArtifact(String name, int version, Object... deps) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(deps));
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), headers(null)), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    private JsonNode definePolicy(String graph, int baseVersion, Object... coordinates) {
        Map<String, Object> body = Map.of(
                "lockfileName", graph,
                "baseVersion", baseVersion,
                "coordinates", List.of(coordinates));
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                "/api/provenance/policies",
                new HttpEntity<>(toJson(body), headers("alice")), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private Map<String, Object> coord(String name, int level, String digest) {
        return Map.of("name", name, "requiredLevel", level,
                "requiredDigest", digest == null ? "" : digest);
    }

    private void submitAttestation(String name, int version, String digest, int level) {
        Map<String, Object> body = Map.of(
                "name", name, "version", version,
                "sourceRepository", "repo-a", "buildDigest", digest,
                "attestationLevel", level);
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                "/api/provenance/attestations",
                new HttpEntity<>(toJson(body), headers("alice")), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void fullProvenanceWorkflowThroughHttpProducesPublishedSnapshot() {
        registerArtifact("app", 1, dependency("lib", 2, 2));
        registerArtifact("lib", 2);
        definePolicy("graph-a", 0, coord("app", 1, ""), coord("lib", 1, ""));
        submitAttestation("app", 1, "sha-app", 3);
        submitAttestation("lib", 2, "sha-lib", 3);

        // 命名锁定图解析。
        Map<String, Object> lockBody = Map.of(
                "lockName", "graph-a", "rootName", "app", "rootVersion", 1,
                "expectedRepositoryVersion", repositoryVersion());
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(toJson(lockBody), headers(null)), JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lock.getBody().path("policyVersion").asInt()).isEqualTo(1);
        long lockId = lock.getBody().path("id").asLong();

        // 诊断合规。
        ResponseEntity<JsonNode> diag = restTemplate.getForEntity(
                "/api/provenance/diagnostics/locks/{id}", JsonNode.class, lockId);
        assertThat(diag.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(diag.getBody().path("compliant").asBoolean()).isTrue();
        assertThat(diag.getBody().path("currentPolicyVersion").asInt()).isEqualTo(1);
        assertThat(diag.getBody().path("provenancePath")).hasSize(2);

        // 发布。
        ResponseEntity<JsonNode> publish = restTemplate.postForEntity(
                "/api/provenance/releases",
                new HttpEntity<>(toJson(Map.of("lockFileId", lockId)), headers("alice")),
                JsonNode.class);
        assertThat(publish.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(publish.getBody().path("provenanceKey").asText()).matches("[0-9a-f]{64}");
        assertThat(publish.getBody().path("entries")).hasSize(2);
        assertThat(publish.getBody().path("entries").get(0).path("name").asText())
                .isEqualTo("app");
        assertThat(publish.getBody().path("entries").get(1).path("attestationId").asLong())
                .isPositive();

        // 发布快照可查询。
        ResponseEntity<JsonNode> release = restTemplate.getForEntity(
                "/api/provenance/releases/locks/{id}", JsonNode.class, lockId);
        assertThat(release.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(release.getBody().path("provenanceKey").asText())
                .isEqualTo(publish.getBody().path("provenanceKey").asText());
    }

    @Test
    void missingAttestationReturns422WithDistinctReasonAndFullPath() {
        registerArtifact("app", 1, dependency("lib", 2, 2));
        registerArtifact("lib", 2);
        definePolicy("graph-a", 0, coord("app", 1, ""), coord("lib", 1, ""));
        submitAttestation("app", 1, "sha-app", 1);
        // lib 未证明。

        Map<String, Object> lockBody = Map.of(
                "lockName", "graph-a", "rootName", "app", "rootVersion", 1,
                "expectedRepositoryVersion", repositoryVersion());
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(toJson(lockBody), headers(null)), JsonNode.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().path("error").asText())
                .isEqualTo("PROVENANCE_POLICY_VIOLATION");
        JsonNode violations = resp.getBody().path("violations");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).path("reason").asText())
                .isEqualTo("MISSING_ATTESTATION");
        assertThat(violations.get(0).path("path"))
                .map(JsonNode::asText)
                .containsExactly("app:1", "lib:2");
    }

    @Test
    void definePolicyWithoutOperatorHeaderReturns400() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Request-Id", uuid());
        Map<String, Object> body = Map.of(
                "lockfileName", "graph-a", "baseVersion", 0,
                "coordinates", List.of(coord("app", 1, "")));
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                "/api/provenance/policies", new HttpEntity<>(toJson(body), h), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void migrationPrevalidationFailureReturns422AndKeepsBindings() {
        registerArtifact("app", 1);
        definePolicy("graph-a", 0, coord("app", 1, ""));
        submitAttestation("app", 1, "d", 1);
        Map<String, Object> lockBody = Map.of(
                "lockName", "graph-a", "rootName", "app", "rootVersion", 1,
                "expectedRepositoryVersion", repositoryVersion());
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(toJson(lockBody), headers(null)), JsonNode.class);
        long lockId = lock.getBody().path("id").asLong();

        // v2 要求等级 9，证明仅等级 1：迁移预校验失败。
        definePolicy("graph-a", 1, coord("app", 9, ""));
        Map<String, Object> migrationBody = Map.of("targets", List.of(
                Map.of("lockFileId", lockId, "targetPolicyVersion", 2)));
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                "/api/provenance/migrations",
                new HttpEntity<>(toJson(migrationBody), headers("alice")), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().path("violations").get(0).path("reason").asText())
                .isEqualTo("LEVEL_INSUFFICIENT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT policy_version FROM lock_file WHERE id = ?", Integer.class, lockId))
                .isEqualTo(1);
    }
}
