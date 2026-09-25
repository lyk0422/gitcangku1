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
 * 漏洞豁免与发布门禁的真实 HTTP 入口测试：公告、双人豁免、结构化 422、
 * 发布快照、撤销与查询，以及 X-Reviewer/X-Request-Id 头语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityControllerHttpTest {

    private static final String FAR_FUTURE = "2099-01-01T00:00:00Z";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM publish_snapshot_entry");
        jdbcTemplate.update("DELETE FROM publish_snapshot");
        jdbcTemplate.update("DELETE FROM vulnerability_exception");
        jdbcTemplate.update("DELETE FROM vulnerability_advisory");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private HttpHeaders headers(String requestId, String reviewer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        if (reviewer != null) {
            h.set("X-Reviewer", reviewer);
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

    private Map<String, Object> dependency(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private void register(String name, int version, Object... deps) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(deps));
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), headers(rid(), null)), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private long createLock() {
        register("app", 1, dependency("lib", 1, 1), dependency("util", 1, 1));
        register("lib", 1);
        register("util", 1);
        String body = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":3}";
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(body, headers(rid(), null)), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().path("id").asLong();
    }

    private String advisoryBody(String vid, String name, int version, String severity) {
        return toJson(Map.of("vulnerabilityId", vid, "artifactName", name,
                "artifactVersion", version, "severity", severity, "expiresAt", FAR_FUTURE));
    }

    private void upsertAdvisory(String vid, String name, int version, String severity) {
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                "/api/security/advisories",
                new HttpEntity<>(advisoryBody(vid, name, version, severity),
                        headers(rid(), null)), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().path("vulnerabilityId").asText()).isEqualTo(vid);
    }

    private String exceptionBody(long lockId, String vid) {
        return toJson(Map.of("lockFileId", lockId, "vulnerabilityId", vid,
                "expiresAt", FAR_FUTURE, "reason", "已评估风险，临时豁免"));
    }

    private JsonNode confirm(long lockId, String vid, String reviewer) {
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                "/api/security/locks/" + lockId + "/exceptions",
                new HttpEntity<>(exceptionBody(lockId, vid), headers(rid(), reviewer)),
                JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @Test
    void fullExceptionAndPublishGateWorkflowOverHttp() {
        long lockId = createLock();
        upsertAdvisory("CVE-LIB-1", "lib", 1, "CRITICAL");
        upsertAdvisory("CVE-UTIL-1", "util", 1, "CRITICAL");

        // 默认拒绝：422 且结构化列出全部命中制品。
        ResponseEntity<JsonNode> blocked = restTemplate.postForEntity(
                "/api/security/locks/" + lockId + "/publish",
                new HttpEntity<>("", headers(rid(), null)), JsonNode.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blocked.getBody().path("error").asText()).isEqualTo("VULNERABILITY_GATE_BLOCKED");
        assertThat(blocked.getBody().path("blocked")).hasSize(2);
        assertThat(blocked.getBody().path("blocked")).extracting(n -> n.path("vulnerabilityId").asText())
                .containsExactlyInAnyOrder("CVE-LIB-1", "CVE-UTIL-1");

        // 双人豁免：alice → PENDING，bob → CONFIRMED。
        JsonNode libFirst = confirm(lockId, "CVE-LIB-1", "alice");
        assertThat(libFirst.path("status").asText()).isEqualTo("PENDING");
        JsonNode libSecond = confirm(lockId, "CVE-LIB-1", "bob");
        assertThat(libSecond.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(libSecond.path("reviewer2").asText()).isEqualTo("bob");
        long exceptionId = libSecond.path("id").asLong();

        confirm(lockId, "CVE-UTIL-1", "alice");
        confirm(lockId, "CVE-UTIL-1", "bob");

        // 命中查询：作用域为 CONFIRMED。
        ResponseEntity<JsonNode> hits = restTemplate.getForEntity(
                "/api/security/locks/" + lockId + "/vulnerabilities", JsonNode.class);
        assertThat(hits.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hits.getBody()).hasSize(2);
        assertThat(hits.getBody()).allSatisfy(n ->
                assertThat(n.path("exceptionStatus").asText()).isEqualTo("CONFIRMED"));

        // 豁免齐备：发布成功，快照复制精确条目。
        ResponseEntity<JsonNode> published = restTemplate.postForEntity(
                "/api/security/locks/" + lockId + "/publish",
                new HttpEntity<>("", headers(rid(), null)), JsonNode.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(published.getBody().path("entries")).hasSize(3);

        // 撤销 lib 豁免后再次发布：仅 lib 阻断，历史快照仍可查。
        ResponseEntity<JsonNode> revoked = restTemplate.postForEntity(
                "/api/security/exceptions/" + exceptionId + "/revoke",
                new HttpEntity<>("", headers(rid(), "alice")), JsonNode.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().path("status").asText()).isEqualTo("REVOKED");

        ResponseEntity<JsonNode> blockedAgain = restTemplate.postForEntity(
                "/api/security/locks/" + lockId + "/publish",
                new HttpEntity<>("", headers(rid(), null)), JsonNode.class);
        assertThat(blockedAgain.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blockedAgain.getBody().path("blocked")).hasSize(1);
        assertThat(blockedAgain.getBody().path("blocked").get(0).path("vulnerabilityId").asText())
                .isEqualTo("CVE-LIB-1");

        ResponseEntity<JsonNode> publishes = restTemplate.getForEntity(
                "/api/security/locks/" + lockId + "/publishes", JsonNode.class);
        assertThat(publishes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publishes.getBody()).hasSize(1);

        ResponseEntity<JsonNode> exceptions = restTemplate.getForEntity(
                "/api/security/locks/" + lockId + "/exceptions", JsonNode.class);
        assertThat(exceptions.getBody()).hasSize(2);
    }

    @Test
    void missingReviewerHeaderReturns400() {
        long lockId = createLock();
        upsertAdvisory("CVE-LIB-1", "lib", 1, "CRITICAL");
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                "/api/security/locks/" + lockId + "/exceptions",
                new HttpEntity<>(exceptionBody(lockId, "CVE-LIB-1"),
                        headers(rid(), null)), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void publishMissingLockReturns404() {
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                "/api/security/locks/9999/publish",
                new HttpEntity<>("", headers(rid(), null)), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void advisoryUpsertIsNaturalIdempotentAndUpdatesSeverity() {
        upsertAdvisory("CVE-LIB-1", "lib", 1, "CRITICAL");
        upsertAdvisory("CVE-LIB-1", "lib", 1, "HIGH");
        ResponseEntity<JsonNode> list = restTemplate.getForEntity(
                "/api/security/advisories", JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);
        assertThat(list.getBody().get(0).path("severity").asText()).isEqualTo("HIGH");
    }
}
