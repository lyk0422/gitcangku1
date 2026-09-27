package com.example.starter.api;

import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 弃用窗口 HTTP 入口测试：状态码、响应结构、告警与 422 违规明细、撤销与重解析端点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfiguration.class)
class DeprecationWindowHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM lock_deprecation_warning");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_deprecation");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
        clock.setInstant(TestClockConfiguration.BASE);
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", UUID.randomUUID().toString());
        return headers;
    }

    private void registerArtifact(String name, int version, String dependenciesJson) {
        String body = "{\"name\":\"" + name + "\",\"version\":" + version
                + ",\"dependencies\":" + dependenciesJson + "}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(body, headers()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private long repositoryVersion() {
        Long v = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        return v == null ? 0 : v;
    }

    private static String deprecateBody(String name, int version, String effectiveAtIso,
                                        int replacement) {
        return "{\"windows\":[{\"name\":\"" + name + "\",\"version\":" + version
                + ",\"effectiveAt\":\"" + effectiveAtIso + "\",\"replacementVersion\":"
                + replacement + ",\"reason\":\"安全修复\"}]}";
    }

    @Test
    void deprecationLifecycleOverHttp() {
        registerArtifact("lib", 1, "[]");
        registerArtifact("lib", 2, "[]");
        registerArtifact("app", 1,
                "[{\"name\":\"lib\",\"minimumVersion\":1,\"maximumVersion\":1}]");

        // 登记弃用窗口：201。
        String effectiveAt = TestClockConfiguration.BASE.plusSeconds(3600).toString();
        ResponseEntity<JsonNode> created = restTemplate.postForEntity("/api/artifacts/deprecations",
                new HttpEntity<>(deprecateBody("lib", 1, effectiveAt, 2), headers()),
                JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().isArray()).isTrue();
        assertThat(created.getBody().get(0).path("revoked").asBoolean()).isFalse();
        assertThat(created.getBody().get(0).path("revokedAt").isNull()).isTrue();

        // 生效前锁定：201 且携带 DEPRECATION_WARNING。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":"
                + repositoryVersion() + "}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, headers()), JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode warnings = lock.getBody().path("deprecationWarnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).path("code").asText()).isEqualTo("DEPRECATION_WARNING");
        assertThat(warnings.get(0).path("replacementVersion").asInt()).isEqualTo(2);

        // 生效后锁定：422 且违规明细稳定排序、字段齐全。
        clock.advance(Duration.ofSeconds(3600));
        ResponseEntity<JsonNode> blocked = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, headers()), JsonNode.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blocked.getBody().path("error").asText()).isEqualTo("DEPRECATION_VIOLATION");
        JsonNode violations = blocked.getBody().path("violations");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).path("name").asText()).isEqualTo("lib");
        assertThat(violations.get(0).path("version").asInt()).isEqualTo(1);
        assertThat(violations.get(0).path("replacementVersion").asInt()).isEqualTo(2);
        assertThat(violations.get(0).path("effectiveAt").asText()).isEqualTo(effectiveAt);

        // 历史锁仍可查询，告警快照不回写。
        long lockId = lock.getBody().path("id").asLong();
        ResponseEntity<JsonNode> historical = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}", JsonNode.class, lockId);
        assertThat(historical.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(historical.getBody().path("deprecationWarnings")).hasSize(1);
    }

    @Test
    void revokeAndReResolveOverHttp() {
        registerArtifact("lib", 1, "[]");
        registerArtifact("lib", 2, "[]");
        registerArtifact("app", 1,
                "[{\"name\":\"lib\",\"minimumVersion\":1,\"maximumVersion\":1}]");

        String effectiveAt = TestClockConfiguration.BASE.plusSeconds(3600).toString();
        restTemplate.postForEntity("/api/artifacts/deprecations",
                new HttpEntity<>(deprecateBody("lib", 1, effectiveAt, 2), headers()),
                JsonNode.class);

        // 撤销未生效窗口：200，记录保留。
        ResponseEntity<JsonNode> revoked = restTemplate.exchange(
                "/api/artifacts/lib/versions/1/deprecation/revoke", HttpMethod.POST,
                new HttpEntity<>("", headers()), JsonNode.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().path("revoked").asBoolean()).isTrue();
        assertThat(revoked.getBody().path("revokedAt").isTextual()).isTrue();

        ResponseEntity<JsonNode> listed = restTemplate.getForEntity(
                "/api/artifacts/deprecations", JsonNode.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(1);
        assertThat(listed.getBody().get(0).path("revoked").asBoolean()).isTrue();

        // 撤销后锁定无告警；重解析生成新锁文件。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":"
                + repositoryVersion() + "}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, headers()), JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lock.getBody().path("deprecationWarnings")).isEmpty();

        long lockId = lock.getBody().path("id").asLong();
        ResponseEntity<JsonNode> reResolved = restTemplate.exchange(
                "/api/artifacts/locks/{id}/re-resolve", HttpMethod.POST,
                new HttpEntity<>("", headers()), JsonNode.class, lockId);
        assertThat(reResolved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reResolved.getBody().path("id").asLong()).isNotEqualTo(lockId);
    }

    @Test
    void invalidDeprecationBatchReturns422AndNothingPersisted() {
        registerArtifact("lib", 1, "[]");
        // 替代版本不存在。
        String effectiveAt = TestClockConfiguration.BASE.plusSeconds(3600).toString();
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/deprecations",
                new HttpEntity<>(deprecateBody("lib", 1, effectiveAt, 9), headers()),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isZero();
    }

    @Test
    void missingRequestIdOnDeprecationReturns400() {
        HttpHeaders noRequestId = new HttpHeaders();
        noRequestId.setContentType(MediaType.APPLICATION_JSON);
        String effectiveAt = TestClockConfiguration.BASE.plusSeconds(3600).toString();
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/deprecations",
                new HttpEntity<>(deprecateBody("lib", 1, effectiveAt, 2), noRequestId),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
