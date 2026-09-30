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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 依赖风险豁免的真实 HTTP 端到端合成验收：
 * 制品 A 含依赖 X@1（HIGH），未豁免锁定 422；甲乙批准仅适用 X@1 的豁免后成功；X@2 仍被拒。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskExceptionHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_entry_exception_approver");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM risk_exception_approval");
        jdbcTemplate.update("DELETE FROM risk_exception");
        jdbcTemplate.update("DELETE FROM artifact_risk");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", rid());
        return headers;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
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

    private void register(String name, int version, Object... dependencies) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(dependencies));
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts", new HttpEntity<>(json(body), headers()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void markRisk(String name, int version, String level) {
        Map<String, Object> body = Map.of("name", name, "version", version,
                "riskLevel", level, "reason", "合成高危依赖");
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/risks", new HttpEntity<>(json(body), headers()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("riskLevel").asText()).isEqualTo(level);
    }

    private long createException(String name, int version) {
        Map<String, Object> body = Map.of(
                "dependencyName", name,
                "dependencyVersion", version,
                "reason", "发布窗口内业务必须使用，补偿控制已上线",
                "expiresAt", Instant.now().plus(7, ChronoUnit.DAYS).toString());
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/risk-exceptions",
                new HttpEntity<>(json(body), headers()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asLong();
    }

    private JsonNode approve(long exceptionId, String approver, int exceptionVersion, HttpStatus expected) {
        Map<String, Object> body = Map.of("approver", approver, "exceptionVersion", exceptionVersion);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/risk-exceptions/{id}/approvals",
                new HttpEntity<>(json(body), headers()), JsonNode.class, exceptionId);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> lock(String bodyJson) {
        return restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(bodyJson, headers()), JsonNode.class);
    }

    @Test
    void syntheticScenarioHighRiskBlockedThenApprovedForExactVersionThenV2Rejected() {
        // 制品 A:1 -> X[1,1]；登记 X:1、X:2 并将二者标记 HIGH；仓库版本为 3 次登记。
        register("A", 1, dependency("X", 1, 1));
        register("X", 1);
        register("X", 2);
        markRisk("X", 1, "HIGH");
        markRisk("X", 2, "HIGH");

        // 1) 未携带豁免锁定 A → 422，且没有生成锁文件。
        String noExceptionLock = "{\"rootName\":\"A\",\"rootVersion\":1,\"expectedRepositoryVersion\":3}";
        ResponseEntity<JsonNode> blocked = lock(noExceptionLock);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blocked.getBody().path("error").asText()).isEqualTo("UNPROCESSABLE_ENTITY");
        assertThat(blocked.getBody().path("message").asText()).contains("X").contains("HIGH");
        assertThat(restTemplate.getForEntity("/api/artifacts/locks", JsonNode.class).getBody())
                .hasSize(0);

        // 2) 创建仅适用 X@1 的豁免，单人批准后仍 422。
        long exceptionId = createException("X", 1);
        JsonNode pending = approve(exceptionId, "jia", 1, HttpStatus.OK);
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        String singleApprovalLock = "{\"rootName\":\"A\",\"rootVersion\":1,\"expectedRepositoryVersion\":3,"
                + "\"exceptions\":[{\"exceptionId\":" + exceptionId + "}]}";
        assertThat(lock(singleApprovalLock).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 3) 第二名不同审批人确认同一申请版本 → APPROVED，锁定成功并固化依据。
        JsonNode approved = approve(exceptionId, "yi", 1, HttpStatus.OK);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("valid").asBoolean()).isTrue();
        assertThat(approved.path("dependencyName").asText()).isEqualTo("X");
        assertThat(approved.path("dependencyVersion").asInt()).isEqualTo(1);

        ResponseEntity<JsonNode> locked = lock(singleApprovalLock);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode xEntry = locked.getBody().path("entries").get(1);
        assertThat(xEntry.path("name").asText()).isEqualTo("X");
        assertThat(xEntry.path("version").asInt()).isEqualTo(1);
        assertThat(xEntry.path("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(xEntry.path("exception").path("exceptionId").asLong()).isEqualTo(exceptionId);
        assertThat(xEntry.path("exception").path("exceptionVersion").asInt()).isEqualTo(1);
        assertThat(xEntry.path("exception").path("approvers").get(0).asText()).isEqualTo("jia");
        assertThat(xEntry.path("exception").path("approvers").get(1).asText()).isEqualTo("yi");

        long lockId = locked.getBody().path("id").asLong();

        // 4) X@2 场景：构造 B:1 -> X[2,2]，同一豁免不适用 X@2 → 422。
        register("B", 1, dependency("X", 2, 2));
        String v2Lock = "{\"rootName\":\"B\",\"rootVersion\":1,\"expectedRepositoryVersion\":4,"
                + "\"exceptions\":[{\"exceptionId\":" + exceptionId + "}]}";
        ResponseEntity<JsonNode> v2Blocked = lock(v2Lock);
        assertThat(v2Blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(v2Blocked.getBody().path("message").asText()).contains("不符");

        // 5) 历史与当前查询：历史锁保留 HIGH 与豁免依据；豁免列表展示状态与适用版本。
        ResponseEntity<JsonNode> history = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}", JsonNode.class, lockId);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody().path("entries").get(1).path("riskLevel").asText())
                .isEqualTo("HIGH");
        assertThat(history.getBody().path("entries").get(1).path("exception").path("approvers"))
                .hasSize(2);

        ResponseEntity<JsonNode> exceptions = restTemplate.getForEntity(
                "/api/artifacts/risk-exceptions", JsonNode.class);
        assertThat(exceptions.getBody()).hasSize(1);
        JsonNode exceptionView = exceptions.getBody().get(0);
        assertThat(exceptionView.path("status").asText()).isEqualTo("APPROVED");
        assertThat(exceptionView.path("dependencyVersion").asInt()).isEqualTo(1);
        assertThat(exceptionView.path("approvals")).hasSize(2);
    }

    @Test
    void invalidRiskLevelReturns400AndRevokedExceptionLocks422() {
        register("A", 1, dependency("X", 1, 1));
        register("X", 1);

        ResponseEntity<JsonNode> badLevel = restTemplate.postForEntity(
                "/api/artifacts/risks",
                new HttpEntity<>(json(Map.of("name", "X", "version", 1, "riskLevel", "SEVERE")),
                        headers()),
                JsonNode.class);
        assertThat(badLevel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        markRisk("X", 1, "HIGH");
        long exceptionId = createException("X", 1);
        approve(exceptionId, "jia", 1, HttpStatus.OK);
        approve(exceptionId, "yi", 1, HttpStatus.OK);

        String lockBody = "{\"rootName\":\"A\",\"rootVersion\":1,\"expectedRepositoryVersion\":2,"
                + "\"exceptions\":[{\"exceptionId\":" + exceptionId + "}]}";
        assertThat(lock(lockBody).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> revoke = restTemplate.postForEntity(
                "/api/artifacts/risk-exceptions/{id}/revoke",
                new HttpEntity<>("", headers()), JsonNode.class, exceptionId);
        assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoke.getBody().path("status").asText()).isEqualTo("REVOKED");

        // 撤销后新锁定被门禁拒绝，历史锁仍可查询。
        assertThat(lock(lockBody).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ResponseEntity<JsonNode> locks = restTemplate.getForEntity(
                "/api/artifacts/locks", JsonNode.class);
        assertThat(locks.getBody()).hasSize(1);
        assertThat(locks.getBody().get(0).path("entries").get(1)
                .path("exception").path("statusAtLock").asText()).isEqualTo("APPROVED");
    }
}
