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
 * 许可证告知与发布门禁的真实 HTTP 入口测试：状态码、422 结构化命中路径与幂等重放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LicenseControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String rid() {
        return UUID.randomUUID().toString();
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
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM release_snapshot_entry");
        jdbcTemplate.update("DELETE FROM release_snapshot_item");
        jdbcTemplate.update("DELETE FROM release_snapshot");
        jdbcTemplate.update("DELETE FROM license_notice_binding");
        jdbcTemplate.update("DELETE FROM license_notice_text");
        jdbcTemplate.update("DELETE FROM license_policy");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private void postArtifact(String name, int version, List<Map<String, Object>> deps) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", deps);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), jsonHeaders(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private static Map<String, Object> dependency(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private long createLock(String rootName, int rootVersion, long expectedVersion) {
        Map<String, Object> body = Map.of("rootName", rootName, "rootVersion", rootVersion,
                "expectedRepositoryVersion", expectedVersion);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(toJson(body), jsonHeaders(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asLong();
    }

    private JsonNode postLicense(String path, Object body, String requestId, HttpStatus expected) {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/licenses" + path,
                new HttpEntity<>(toJson(body), jsonHeaders(requestId)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody();
    }

    @Test
    void fullLicenseReleaseWorkflowOverHttp() {
        // app:1 -> util:1；两个制品。
        postArtifact("app", 1, List.of(dependency("util", 1, 1)));
        postArtifact("util", 1, List.of());
        long lockId = createLock("app", 1, 2);

        // COORDINATE 策略：util MIT 必须告知。
        postLicense("/policies", Map.of(
                "scopeType", "COORDINATE",
                "artifactName", "util",
                "licenseId", "MIT",
                "action", "NOTICE_REQUIRED"), rid(), HttpStatus.CREATED);

        // 告知文本 DRAFT -> APPROVED。
        postLicense("/notices", Map.of(
                "noticeKey", "mit-notice",
                "version", 1,
                "licenseId", "MIT",
                "body", "MIT license notice text",
                "regions", List.of("cn", "us")), rid(), HttpStatus.CREATED);
        JsonNode approved = postLicense("/notices/mit-notice/versions/1/approve",
                "", rid(), HttpStatus.OK);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("regions")).hasSize(2);

        // 绑定。
        postLicense("/bindings", Map.of(
                "scopeType", "COORDINATE",
                "artifactName", "util",
                "licenseId", "MIT",
                "noticeKey", "mit-notice",
                "noticeVersion", 1), rid(), HttpStatus.CREATED);

        // 预检：无缺失。
        ResponseEntity<JsonNode> check = restTemplate.getForEntity(
                "/api/licenses/locks/{id}/check?regions=cn", JsonNode.class, lockId);
        assertThat(check.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(check.getBody().path("missing")).isEmpty();
        assertThat(check.getBody().path("hits")).hasSize(1);

        // 发布（幂等键）。
        String releaseKey = rid();
        Map<String, Object> releaseBody = Map.of(
                "lockFileIds", List.of(lockId),
                "regions", List.of("cn"));
        JsonNode release = postLicense("/releases", releaseBody, releaseKey, HttpStatus.CREATED);
        long releaseId = release.path("releaseId").asLong();
        assertThat(release.path("items")).hasSize(1);
        assertThat(release.path("items").get(0).path("entries")).hasSize(2);

        // 同键同参重放，返回首次完整响应。
        JsonNode replay = postLicense("/releases", releaseBody, releaseKey, HttpStatus.CREATED);
        assertThat(replay.path("releaseId").asLong()).isEqualTo(releaseId);

        // 历史快照查询。
        ResponseEntity<JsonNode> fetched = restTemplate.getForEntity(
                "/api/licenses/releases/{id}", JsonNode.class, releaseId);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().path("items").get(0).path("entries").get(0)
                .path("name").asText()).isEqualTo("app");
    }

    @Test
    void missingNoticeReleaseReturns422WithStableDetails() {
        postArtifact("app", 1, List.of(dependency("util", 1, 1)));
        postArtifact("util", 1, List.of());
        long lockId = createLock("app", 1, 2);

        postLicense("/policies", Map.of(
                "scopeType", "COORDINATE",
                "artifactName", "util",
                "licenseId", "GPL-3.0",
                "action", "NOTICE_REQUIRED"), rid(), HttpStatus.CREATED);
        // 不登记文本、不绑定，直接发布。

        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/licenses/releases",
                new HttpEntity<>(toJson(Map.of(
                        "lockFileIds", List.of(lockId),
                        "regions", List.of("CN"))),
                        jsonHeaders(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().path("error").asText()).isEqualTo("UNPROCESSABLE_ENTITY");
        JsonNode details = response.getBody().path("details");
        assertThat(details.isArray()).isTrue();
        assertThat(details).hasSize(1);
        JsonNode missing = details.get(0).path("missing").get(0);
        assertThat(missing.path("reason").asText()).isEqualTo("NOTICE_MISSING");
        assertThat(missing.path("name").asText()).isEqualTo("util");
        assertThat(missing.path("direct").asBoolean()).isTrue();
        assertThat(missing.path("path")).hasSize(2);
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        postArtifact("app", 1, List.of());
        long lockId = createLock("app", 1, 1);
        String key = rid();

        postLicense("/releases", Map.of("lockFileIds", List.of(lockId),
                "regions", List.of("CN")), key, HttpStatus.CREATED);

        ResponseEntity<JsonNode> conflict = restTemplate.postForEntity("/api/licenses/releases",
                new HttpEntity<>(toJson(Map.of("lockFileIds", List.of(lockId),
                        "regions", List.of("JP"))), jsonHeaders(key)), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unknownReleaseReturns404AndMissingRequestIdReturns400() {
        ResponseEntity<JsonNode> notFound = restTemplate.getForEntity(
                "/api/licenses/releases/9999", JsonNode.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> badRequest = restTemplate.postForEntity("/api/licenses/policies",
                new HttpEntity<>(toJson(Map.of(
                        "scopeType", "COORDINATE",
                        "artifactName", "x",
                        "licenseId", "MIT",
                        "action", "ALLOWED")), jsonHeaders(null)), JsonNode.class);
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
