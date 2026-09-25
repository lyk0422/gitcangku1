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
        jdbcTemplate.update("DELETE FROM lock_file_mirror");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_mirror");
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
    // 镜像源端点
    // ------------------------------------------------------------------

    private String mirrorBody(Object... mirrors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mirrors", List.of(mirrors));
        return toJson(body);
    }

    private Map<String, Object> mirror(String id, int priority) {
        Map<String, Object> mirror = new LinkedHashMap<>();
        mirror.put("mirrorId", id);
        mirror.put("priority", priority);
        return mirror;
    }

    private void registerArtifactViaHttp(String name, int version) {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody(name, version),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
    }

    @Test
    void mirrorRegistrationLockAndFailoverWorkflow() {
        registerArtifactViaHttp("app", 1);

        // 登记镜像：201，按优先级升序返回。
        ResponseEntity<JsonNode> registered = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/mirrors",
                new HttpEntity<>(mirrorBody(mirror("m-slow", 3), mirror("m-fast", 1)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody().get(0).path("mirrorId").asText()).isEqualTo("m-fast");
        assertThat(registered.getBody().get(1).path("mirrorId").asText()).isEqualTo("m-slow");

        // 登记明细查询。
        ResponseEntity<JsonNode> detail = restTemplate.getForEntity(
                "/api/artifacts/app/versions/1/mirrors", JsonNode.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).hasSize(2);

        // 锁定：条目固化可用镜像快照。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"expectedRepositoryVersion\":1}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long lockId = lock.getBody().path("id").asLong();
        JsonNode mirrors = lock.getBody().path("entries").get(0).path("mirrors");
        assertThat(mirrors).hasSize(2);
        assertThat(mirrors.get(0).path("mirrorId").asText()).isEqualTo("m-fast");

        // 按锁文件查询镜像清单。
        ResponseEntity<JsonNode> lockMirrors = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}/mirrors", JsonNode.class, lockId);
        assertThat(lockMirrors.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lockMirrors.getBody().get(0).path("mirrors")).hasSize(2);

        // 故障切换：返回最高优先级可用镜像。
        ResponseEntity<JsonNode> failover = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}/mirrors/failover?name=app", JsonNode.class, lockId);
        assertThat(failover.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failover.getBody().path("mirrorId").asText()).isEqualTo("m-fast");

        // 标记 m-fast 不可用：故障切换回退到 m-slow，锁文件快照不变。
        ResponseEntity<JsonNode> toggled = restTemplate.exchange(
                "/api/artifacts/app/versions/1/mirrors/m-fast/availability",
                HttpMethod.PUT,
                new HttpEntity<>("{\"available\":false}",
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(toggled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toggled.getBody().path("available").asBoolean()).isFalse();

        ResponseEntity<JsonNode> fallback = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}/mirrors/failover?name=app", JsonNode.class, lockId);
        assertThat(fallback.getBody().path("mirrorId").asText()).isEqualTo("m-slow");
        assertThat(restTemplate.getForEntity("/api/artifacts/locks/{id}/mirrors",
                JsonNode.class, lockId).getBody().get(0).path("mirrors")).hasSize(2);

        // 全部不可用：422。
        restTemplate.exchange("/api/artifacts/app/versions/1/mirrors/m-slow/availability",
                HttpMethod.PUT,
                new HttpEntity<>("{\"available\":false}",
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        ResponseEntity<JsonNode> none = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}/mirrors/failover?name=app", JsonNode.class, lockId);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(none.getBody().path("error").asText()).isEqualTo("UNPROCESSABLE_ENTITY");

        // 名称不在锁文件解析集合：404。
        ResponseEntity<JsonNode> unknown = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}/mirrors/failover?name=ghost", JsonNode.class, lockId);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void mirrorRegistrationIdempotentReplayViaHttp() {
        registerArtifactViaHttp("app", 1);
        String rid = UUID.randomUUID().toString();
        HttpEntity<String> request = new HttpEntity<>(
                mirrorBody(mirror("m1", 1)), jsonHeaders(rid));

        ResponseEntity<JsonNode> first = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/mirrors", request, JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> replay = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/mirrors", request, JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        // 同 requestId 异参：409。
        ResponseEntity<JsonNode> conflict = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/mirrors",
                new HttpEntity<>(mirrorBody(mirror("m2", 1)), jsonHeaders(rid)), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void mirrorRegistrationOnMissingVersionReturns404() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/ghost/versions/1/mirrors",
                new HttpEntity<>(mirrorBody(mirror("m1", 1)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void moreThanThreeMirrorsInOneRequestReturns400() {
        registerArtifactViaHttp("app", 1);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/mirrors",
                new HttpEntity<>(mirrorBody(mirror("m1", 1), mirror("m2", 2),
                                mirror("m3", 3), mirror("m4", 4)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
