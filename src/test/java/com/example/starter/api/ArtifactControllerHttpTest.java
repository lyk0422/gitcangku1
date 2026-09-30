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
        jdbcTemplate.update("DELETE FROM lock_file_exception");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM risk_exception_approval");
        jdbcTemplate.update("DELETE FROM risk_exception");
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

    @Test
    void highRiskDependencyRequiresDualApprovedExactVersionException() {
        // A:1 -> X@1.0（X 版本 1，区间 [1,1]）；登记 A、X 后把 X:1 标记为 HIGH。
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("A", 1, dependency("X", 1, 1)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("X", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        ResponseEntity<JsonNode> marked = restTemplate.exchange(
                "/api/artifacts/X/versions/1/risk",
                HttpMethod.POST,
                new HttpEntity<>("{\"riskLevel\":\"HIGH\"}",
                        jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(marked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(marked.getBody().path("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(marked.getBody().path("repositoryVersion").asLong()).isEqualTo(3L);

        // 未豁免锁定 → 422，且不产生锁文件。
        String lockBody = "{\"rootName\":\"A\",\"rootVersion\":1,\"expectedRepositoryVersion\":3}";
        ResponseEntity<JsonNode> blocked = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blocked.getBody().path("message").asText()).contains("X:1").contains("HIGH");

        // 创建豁免申请：仅适用 X@1.0。
        String createBody = "{\"dependencyName\":\"X\",\"dependencyVersion\":1,"
                + "\"expiresAt\":\"2027-01-01T00:00:00Z\",\"reason\":\"大客户兼容性窗口\"}";
        ResponseEntity<JsonNode> created = restTemplate.postForEntity(
                "/api/dependency-exceptions",
                new HttpEntity<>(createBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long exceptionId = created.getBody().path("id").asLong();
        assertThat(created.getBody().path("status").asText()).isEqualTo("PENDING");

        // 甲批准后仍 422。
        ResponseEntity<JsonNode> firstApproval = restTemplate.postForEntity(
                "/api/dependency-exceptions/{id}/approvals",
                new HttpEntity<>("{\"approver\":\"jia\",\"contentVersion\":1}",
                        jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class, exceptionId);
        assertThat(firstApproval.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstApproval.getBody().path("status").asText()).isEqualTo("PENDING");

        String lockWithException = "{\"rootName\":\"A\",\"rootVersion\":1,"
                + "\"expectedRepositoryVersion\":3,\"exceptions\":[" + exceptionId + "]}";
        ResponseEntity<JsonNode> stillBlocked = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockWithException, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(stillBlocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 同一审批人重复确认 → 422；乙批准另一人后生效。
        ResponseEntity<JsonNode> duplicateApprover = restTemplate.postForEntity(
                "/api/dependency-exceptions/{id}/approvals",
                new HttpEntity<>("{\"approver\":\"jia\"}",
                        jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class, exceptionId);
        assertThat(duplicateApprover.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        ResponseEntity<JsonNode> secondApproval = restTemplate.postForEntity(
                "/api/dependency-exceptions/{id}/approvals",
                new HttpEntity<>("{\"approver\":\"yi\"}",
                        jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class, exceptionId);
        assertThat(secondApproval.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondApproval.getBody().path("status").asText()).isEqualTo("APPROVED");
        assertThat(secondApproval.getBody().path("approvers").get(0).asText()).isEqualTo("jia");
        assertThat(secondApproval.getBody().path("approvers").get(1).asText()).isEqualTo("yi");

        // 双人批准后锁定成功，条目固化 HIGH 与豁免 ID，响应携带豁免依据快照。
        ResponseEntity<JsonNode> locked = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockWithException, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(locked.getBody().path("entries")).hasSize(2);
        JsonNode xEntry = locked.getBody().path("entries").get(1);
        assertThat(xEntry.path("name").asText()).isEqualTo("X");
        assertThat(xEntry.path("version").asInt()).isEqualTo(1);
        assertThat(xEntry.path("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(xEntry.path("exceptionId").asLong()).isEqualTo(exceptionId);
        JsonNode evidence = locked.getBody().path("exceptions").get(0);
        assertThat(evidence.path("dependencyName").asText()).isEqualTo("X");
        assertThat(evidence.path("dependencyVersion").asInt()).isEqualTo(1);
        assertThat(evidence.path("status").asText()).isEqualTo("APPROVED");
        assertThat(evidence.path("approvers").get(0).asText()).isEqualTo("jia");
        assertThat(evidence.path("approvers").get(1).asText()).isEqualTo("yi");

        // X@1.1（X 版本 2，HIGH）：用 X@1.0 的豁免 → 422 版本不符。
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("A", 2, dependency("X", 2, 2)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("X", 2),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.exchange("/api/artifacts/X/versions/2/risk",
                HttpMethod.POST,
                new HttpEntity<>("{\"riskLevel\":\"HIGH\"}",
                        jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        String lockA2 = "{\"rootName\":\"A\",\"rootVersion\":2,\"expectedRepositoryVersion\":6,"
                + "\"exceptions\":[" + exceptionId + "]}";
        ResponseEntity<JsonNode> wrongVersion = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockA2, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(wrongVersion.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(wrongVersion.getBody().path("message").asText()).contains("版本不符");

        // 撤销豁免后新锁定 422，历史锁查询仍保留 APPROVED 依据。
        ResponseEntity<JsonNode> revoked = restTemplate.postForEntity(
                "/api/dependency-exceptions/{id}/revocation",
                new HttpEntity<>("{\"revoker\":\"sec\"}",
                        jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class, exceptionId);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().path("status").asText()).isEqualTo("REVOKED");
        // 撤销后仓库版本已前进到 6：用当前版本号重新锁 A:1，命中的是豁免撤销门禁而非版本门禁。
        String lockA1AtVersion6 = "{\"rootName\":\"A\",\"rootVersion\":1,\"expectedRepositoryVersion\":6,"
                + "\"exceptions\":[" + exceptionId + "]}";
        ResponseEntity<JsonNode> afterRevoke = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockA1AtVersion6, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(afterRevoke.getBody().path("message").asText()).contains("撤销");

        ResponseEntity<JsonNode> exceptionView = restTemplate.getForEntity(
                "/api/dependency-exceptions/{id}", JsonNode.class, exceptionId);
        assertThat(exceptionView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exceptionView.getBody().path("status").asText()).isEqualTo("REVOKED");

        ResponseEntity<JsonNode> historyList = restTemplate.getForEntity(
                "/api/artifacts/locks", JsonNode.class);
        assertThat(historyList.getBody()).hasSize(1);
        assertThat(historyList.getBody().get(0).path("exceptions").get(0).path("status").asText())
                .isEqualTo("APPROVED");
    }
}
