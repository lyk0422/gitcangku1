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
        jdbcTemplate.update("DELETE FROM lock_file_edge");
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

    // ------------------------------------------------------------------
    // 私有作用域（新锁入口 /locks/scoped）
    // ------------------------------------------------------------------

    private Map<String, Object> scopedDependency(String name, int min, int max, String scope) {
        Map<String, Object> dep = dependency(name, min, max);
        dep.put("scope", scope);
        return dep;
    }

    private void registerSyntheticScopedGraphViaHttp() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("root", 1, dependency("x", 1, 1),
                        scopedDependency("p", 1, 1, "PRIVATE")),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("p", 1, dependency("x", 2, 2)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("x", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("x", 2),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
    }

    @Test
    void scopedLockWorkflowViaHttp() {
        registerSyntheticScopedGraphViaHttp();

        String lockBody = "{\"rootName\":\"root\",\"rootVersion\":1,\"expectedRepositoryVersion\":4}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks/scoped",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lock.getBody().path("mode").asText()).isEqualTo("SCOPED");
        assertThat(lock.getBody().path("entries")).hasSize(4);
        // 父域 x@1 与子域 x@2 同时成立。
        boolean parentX = false;
        boolean childX = false;
        for (JsonNode entry : lock.getBody().path("entries")) {
            if (entry.path("name").asText().equals("x") && entry.path("scopePath").isEmpty()) {
                parentX = entry.path("version").asInt() == 1;
            }
            if (entry.path("name").asText().equals("x")
                    && entry.path("scopePath").size() == 1
                    && entry.path("scopePath").get(0).asText().equals("root")) {
                childX = entry.path("version").asInt() == 2;
            }
        }
        assertThat(parentX).isTrue();
        assertThat(childX).isTrue();
        // 冻结的边包含 PRIVATE 作用域。
        boolean privateEdge = false;
        for (JsonNode edge : lock.getBody().path("edges")) {
            if (edge.path("name").asText().equals("p")) {
                privateEdge = edge.path("scope").asText().equals("PRIVATE");
            }
        }
        assertThat(privateEdge).isTrue();

        long lockId = lock.getBody().path("id").asLong();
        ResponseEntity<JsonNode> one = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}", JsonNode.class, lockId);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody().path("mode").asText()).isEqualTo("SCOPED");
        assertThat(one.getBody().path("entries")).hasSize(4);
    }

    @Test
    void scopedLockIdempotentReplayViaHttp() {
        registerSyntheticScopedGraphViaHttp();
        String rid = UUID.randomUUID().toString();
        String lockBody = "{\"rootName\":\"root\",\"rootVersion\":1,\"expectedRepositoryVersion\":4}";

        ResponseEntity<JsonNode> first = restTemplate.postForEntity("/api/artifacts/locks/scoped",
                new HttpEntity<>(lockBody, jsonHeaders(rid)), JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> replay = restTemplate.postForEntity("/api/artifacts/locks/scoped",
                new HttpEntity<>(lockBody, jsonHeaders(rid)), JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("id").asLong())
                .isEqualTo(first.getBody().path("id").asLong());

        String otherBody = "{\"rootName\":\"root\",\"rootVersion\":1,\"expectedRepositoryVersion\":0}";
        ResponseEntity<JsonNode> conflict = restTemplate.postForEntity("/api/artifacts/locks/scoped",
                new HttpEntity<>(otherBody, jsonHeaders(rid)), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invalidDependencyScopeReturns400() {
        String body = "{\"name\":\"app\",\"version\":1,\"dependencies\":["
                + "{\"name\":\"lib\",\"minimumVersion\":1,\"maximumVersion\":1,"
                + "\"scope\":\"INTERNAL\"}]}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerWithMismatchedExpectedRepositoryVersionReturns409() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        String body = "{\"name\":\"lib\",\"version\":1,\"dependencies\":[],"
                + "\"expectedRepositoryVersion\":0}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
