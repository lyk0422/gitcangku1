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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 许可证策略相关 HTTP 入口测试：状态码、请求头、422 违规响应体与诊断查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LicensePolicyHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM namespace_policy_license");
        jdbcTemplate.update("DELETE FROM namespace_policy");
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

    private void register(String name, int version) {
        String body = "{\"name\":\"" + name + "\",\"version\":" + version
                + ",\"dependencies\":[]}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(body, jsonHeaders(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void setLicense(String name, int version, String license) {
        String body = license == null ? "{}" : "{\"license\":\"" + license + "\"}";
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/artifacts/" + name + "/versions/" + version + "/license",
                HttpMethod.PUT, new HttpEntity<>(body, jsonHeaders(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void setPolicy(String namespace, long expectedVersion, boolean rejectUnknown,
                           String allowedJson) {
        String body = "{\"namespace\":\"" + namespace + "\",\"expectedVersion\":"
                + expectedVersion + ",\"rejectUnknown\":" + rejectUnknown
                + ",\"allowedLicenses\":" + allowedJson + "}";
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/artifacts/policies",
                HttpMethod.PUT, new HttpEntity<>(body, jsonHeaders(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void setLicenseViaHttpReturns200AndReplays() {
        register("lib", 1);
        String rid = rid();
        HttpEntity<String> request = new HttpEntity<>("{\"license\":\"MIT\"}", jsonHeaders(rid));

        ResponseEntity<JsonNode> first = restTemplate.exchange(
                "/api/artifacts/lib/versions/1/license", HttpMethod.PUT, request, JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().path("license").asText()).isEqualTo("MIT");
        assertThat(first.getBody().path("repositoryVersion").asLong()).isEqualTo(2L);

        ResponseEntity<JsonNode> replay = restTemplate.exchange(
                "/api/artifacts/lib/versions/1/license", HttpMethod.PUT, request, JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("repositoryVersion").asLong()).isEqualTo(2L);
    }

    @Test
    void setLicenseOnWithdrawnVersionReturns409() {
        register("lib", 1);
        restTemplate.exchange("/api/artifacts/lib/versions/1/withdraw", HttpMethod.POST,
                new HttpEntity<>("", jsonHeaders(rid())), JsonNode.class);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/artifacts/lib/versions/1/license", HttpMethod.PUT,
                new HttpEntity<>("{\"license\":\"MIT\"}", jsonHeaders(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void policyCrudAndStaleVersionConflict() {
        setPolicy("lib", 0, true, "[\"MIT\"]");
        ResponseEntity<JsonNode> queried = restTemplate.getForEntity(
                "/api/artifacts/policies/lib", JsonNode.class);
        assertThat(queried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queried.getBody().path("version").asLong()).isEqualTo(1L);
        assertThat(queried.getBody().path("rejectUnknown").asBoolean()).isTrue();
        assertThat(queried.getBody().path("allowedLicenses").get(0).asText()).isEqualTo("MIT");

        // 过期 expectedVersion → 409。
        String stale = "{\"namespace\":\"lib\",\"expectedVersion\":0,"
                + "\"rejectUnknown\":false,\"allowedLicenses\":[]}";
        ResponseEntity<JsonNode> conflict = restTemplate.exchange("/api/artifacts/policies",
                HttpMethod.PUT, new HttpEntity<>(stale, jsonHeaders(rid())), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 未配置命名空间 → 404。
        ResponseEntity<JsonNode> missing = restTemplate.getForEntity(
                "/api/artifacts/policies/ghost", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lockViolationReturns422WithSortedDiagnostics() {
        register("app", 1);
        // app -> zlib 且 alib：手工插入依赖。
        jdbcTemplate.update("INSERT INTO artifact_dependency (artifact_id, name, "
                        + "minimum_version, maximum_version) "
                        + "SELECT id, 'zlib', 1, 1 FROM artifact WHERE name = 'app'");
        jdbcTemplate.update("INSERT INTO artifact_dependency (artifact_id, name, "
                        + "minimum_version, maximum_version) "
                        + "SELECT id, 'alib', 1, 1 FROM artifact WHERE name = 'app'");
        register("zlib", 1);
        register("alib", 1);
        setPolicy("zlib", 0, true, "[\"MIT\"]");
        setPolicy("alib", 0, true, "[\"MIT\"]");

        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,"
                + "\"expectedRepositoryVersion\":5}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().path("error").asText()).isEqualTo("POLICY_VIOLATION");
        JsonNode violations = response.getBody().path("violations");
        assertThat(violations).hasSize(2);
        // 稳定排序：alib 在 zlib 前。
        assertThat(violations.get(0).path("name").asText()).isEqualTo("alib");
        assertThat(violations.get(1).path("name").asText()).isEqualTo("zlib");
        assertThat(violations.get(0).path("reason").asText())
                .isEqualTo("UNKNOWN_LICENSE_REJECTED");
        // 未生成锁文件。
        ResponseEntity<JsonNode> locks = restTemplate.getForEntity(
                "/api/artifacts/locks", JsonNode.class);
        assertThat(locks.getBody()).isEmpty();
    }

    @Test
    void diagnoseEndpointReturnsViolationsWithoutPersisting() {
        register("app", 1);
        jdbcTemplate.update("INSERT INTO artifact_dependency (artifact_id, name, "
                        + "minimum_version, maximum_version) "
                        + "SELECT id, 'lib', 1, 1 FROM artifact WHERE name = 'app'");
        register("lib", 1);
        setPolicy("lib", 0, true, "[\"MIT\"]");

        String body = "{\"rootName\":\"app\",\"rootVersion\":1,"
                + "\"expectedRepositoryVersion\":3}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/locks/diagnose",
                new HttpEntity<>(body, jsonHeaders(null)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).path("name").asText()).isEqualTo("lib");
        assertThat(response.getBody().get(0).path("license").isNull()).isTrue();

        // 修复许可证后诊断通过。
        setLicense("lib", 1, "MIT");
        ResponseEntity<JsonNode> clean = restTemplate.postForEntity(
                "/api/artifacts/locks/diagnose",
                new HttpEntity<>(body, jsonHeaders(null)), JsonNode.class);
        assertThat(clean.getBody()).isEmpty();
        // 诊断不产生锁文件。
        assertThat(restTemplate.getForEntity("/api/artifacts/locks", JsonNode.class)
                .getBody()).isEmpty();
    }

    @Test
    void lockSuccessFreezesLicenseSnapshotInResponse() {
        register("app", 1);
        register("lib", 1);
        jdbcTemplate.update("INSERT INTO artifact_dependency (artifact_id, name, "
                        + "minimum_version, maximum_version) "
                        + "SELECT id, 'lib', 1, 1 FROM artifact WHERE name = 'app'");
        setLicense("lib", 1, "MIT");
        setPolicy("lib", 0, true, "[\"MIT\"]");

        // 仓库版本：register app=1, register lib=2, setLicense=3, setPolicy=4（JDBC 直插依赖不计）。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,"
                + "\"expectedRepositoryVersion\":4}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(rid())), JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode libEntry = lock.getBody().path("entries").get(1);
        assertThat(libEntry.path("name").asText()).isEqualTo("lib");
        assertThat(libEntry.path("license").asText()).isEqualTo("MIT");
        assertThat(libEntry.path("policyVersion").asLong()).isEqualTo(1L);

        // 修改许可证与策略后，历史锁文件查询仍返回固化值。
        setLicense("lib", 1, "GPL-3.0");
        setPolicy("lib", 1, true, "[\"Apache-2.0\"]");
        ResponseEntity<JsonNode> historical = restTemplate.getForEntity(
                "/api/artifacts/locks/" + lock.getBody().path("id").asLong(), JsonNode.class);
        JsonNode historicalLib = historical.getBody().path("entries").get(1);
        assertThat(historicalLib.path("license").asText()).isEqualTo("MIT");
        assertThat(historicalLib.path("policyVersion").asLong()).isEqualTo(1L);
    }
}
