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
 * 私有作用域锁定的真实 HTTP 入口测试：状态码、X-Operator 头、JSON 结构与幂等重放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScopedLockControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM scoped_lock_edge");
        jdbcTemplate.update("DELETE FROM scoped_lock_entry");
        jdbcTemplate.update("DELETE FROM scoped_lock_scope");
        jdbcTemplate.update("DELETE FROM scoped_lock");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static HttpHeaders headers(String requestId, String operator) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", requestId);
        if (operator != null) {
            headers.set("X-Operator", operator);
        }
        return headers;
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> dependency(String name, int min, int max, String scope) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        if (scope != null) {
            dep.put("scope", scope);
        }
        return dep;
    }

    private void register(String name, int version, Object... dependencies) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(dependencies));
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), headers(UUID.randomUUID().toString(), null)),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void registerSyntheticGraph() {
        register("app", 1, dependency("x", 1, 1, null), dependency("p", 1, 1, "PRIVATE"));
        register("x", 1);
        register("x", 2);
        register("p", 1, dependency("x", 2, 2, null));
    }

    @Test
    void scopedLockWorkflowOverHttp() {
        registerSyntheticGraph();
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":4}";

        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks/scoped",
                new HttpEntity<>(lockBody, headers(UUID.randomUUID().toString(), "tester")),
                JsonNode.class);

        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = lock.getBody();
        assertThat(body.path("repositoryVersion").asLong()).isEqualTo(4L);
        assertThat(body.path("scopes")).hasSize(2);
        JsonNode rootScope = body.path("scopes").get(0);
        assertThat(rootScope.path("depth").asInt()).isEqualTo(1);
        assertThat(rootScope.path("path")).isEmpty();
        assertThat(rootScope.path("entries").toString())
                .contains("\"name\":\"x\",\"version\":1");
        JsonNode childScope = body.path("scopes").get(1);
        assertThat(childScope.path("parentArtifactName").asText()).isEqualTo("app");
        assertThat(childScope.path("path").get(0).asText()).isEqualTo("app");
        assertThat(childScope.path("entries").toString())
                .contains("\"name\":\"x\",\"version\":2");
        assertThat(body.path("edges")).hasSize(3);

        long lockId = body.path("id").asLong();
        ResponseEntity<JsonNode> one = restTemplate.getForEntity(
                "/api/artifacts/locks/scoped/{id}", JsonNode.class, lockId);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody().path("scopes")).hasSize(2);

        ResponseEntity<JsonNode> list = restTemplate.getForEntity(
                "/api/artifacts/locks/scoped", JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);

        ResponseEntity<JsonNode> missing = restTemplate.getForEntity(
                "/api/artifacts/locks/scoped/9999", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void scopedLockReplayReturnsSameResponseAndOperatorChangesIdentity() {
        registerSyntheticGraph();
        String rid = UUID.randomUUID().toString();
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":4}";

        ResponseEntity<JsonNode> first = restTemplate.postForEntity("/api/artifacts/locks/scoped",
                new HttpEntity<>(lockBody, headers(rid, "alice")), JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 同键同参同操作者：重放首次完整响应。
        ResponseEntity<JsonNode> replay = restTemplate.postForEntity("/api/artifacts/locks/scoped",
                new HttpEntity<>(lockBody, headers(rid, "alice")), JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("id").asLong())
                .isEqualTo(first.getBody().path("id").asLong());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock", Integer.class))
                .isEqualTo(1);

        // 同键同参但操作者不同：身份不同，409。
        ResponseEntity<JsonNode> otherOperator = restTemplate.postForEntity(
                "/api/artifacts/locks/scoped",
                new HttpEntity<>(lockBody, headers(rid, "bob")), JsonNode.class);
        assertThat(otherOperator.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void withdrawWithExpectedRepositoryVersionBody() {
        register("app", 1);

        ResponseEntity<JsonNode> mismatch = restTemplate.exchange(
                "/api/artifacts/app/versions/1/withdraw", HttpMethod.POST,
                new HttpEntity<>("{\"expectedRepositoryVersion\":0}",
                        headers(UUID.randomUUID().toString(), null)), JsonNode.class);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<JsonNode> ok = restTemplate.exchange(
                "/api/artifacts/app/versions/1/withdraw", HttpMethod.POST,
                new HttpEntity<>("{\"expectedRepositoryVersion\":1}",
                        headers(UUID.randomUUID().toString(), null)), JsonNode.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().path("withdrawn").asBoolean()).isTrue();
    }

    @Test
    void registerWithExpectedRepositoryVersion() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "app");
        body.put("version", 1);
        body.put("dependencies", List.of());
        body.put("expectedRepositoryVersion", 5);
        ResponseEntity<JsonNode> mismatch = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), headers(UUID.randomUUID().toString(), null)),
                JsonNode.class);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        body.put("expectedRepositoryVersion", 0);
        ResponseEntity<JsonNode> ok = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), headers(UUID.randomUUID().toString(), null)),
                JsonNode.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().path("repositoryVersion").asLong()).isEqualTo(1L);
    }

    @Test
    void invalidDependencyScopeReturns400() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "app");
        body.put("version", 1);
        body.put("dependencies", List.of(dependency("x", 1, 1, "INTERNAL")));
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), headers(UUID.randomUUID().toString(), null)),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
