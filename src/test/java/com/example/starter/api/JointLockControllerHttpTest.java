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
 * 多根联合锁定 HTTP 入口测试：状态码、JSON 结构、根集合换序幂等与查询端点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JointLockControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_edge");
        jdbcTemplate.update("DELETE FROM lock_file_root");
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

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void register(String name, int version, Object... dependencies) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", List.of(dependencies));
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private Map<String, Object> dependency(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private Map<String, Object> root(String name, int version) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", name);
        root.put("version", version);
        return root;
    }

    private String jointLockBody(long expectedRepositoryVersion, Object... roots) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roots", List.of(roots));
        body.put("expectedRepositoryVersion", expectedRepositoryVersion);
        return toJson(body);
    }

    private ResponseEntity<JsonNode> postJointLock(String body, String requestId) {
        return restTemplate.postForEntity("/api/artifacts/locks/joint",
                new HttpEntity<>(body, jsonHeaders(requestId)), JsonNode.class);
    }

    @Test
    void jointLockViaHttpReturns201WithFrozenGraph() {
        // 题干合成场景：根 a 需 x[1,3]，根 b 需 x[1,2]，x3、x2 均在，联合锁应选 x2。
        register("a", 1, dependency("x", 1, 3));
        register("b", 1, dependency("x", 1, 2));
        register("x", 3);
        register("x", 2);

        ResponseEntity<JsonNode> lock = postJointLock(
                jointLockBody(4L, root("a", 1), root("b", 1)), UUID.randomUUID().toString());
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = lock.getBody();
        long lockId = json.path("id").asLong();
        assertThat(json.path("repositoryVersion").asLong()).isEqualTo(4L);
        assertThat(json.path("roots")).hasSize(2);
        assertThat(json.path("roots").get(0).path("name").asText()).isEqualTo("a");
        assertThat(json.path("roots").get(1).path("name").asText()).isEqualTo("b");
        assertThat(json.path("entries")).hasSize(3);
        assertThat(json.path("entries").get(2).path("name").asText()).isEqualTo("x");
        assertThat(json.path("entries").get(2).path("version").asInt()).isEqualTo(2);
        assertThat(json.path("edges")).hasSize(2);
        assertThat(json.path("edges").get(0).path("fromName").asText()).isEqualTo("a");
        assertThat(json.path("edges").get(0).path("toName").asText()).isEqualTo("x");
        assertThat(json.path("edges").get(0).path("minimumVersion").asInt()).isEqualTo(1);
        assertThat(json.path("edges").get(0).path("maximumVersion").asInt()).isEqualTo(3);

        // 联合锁查询端点：列表与单查。
        ResponseEntity<JsonNode> list = restTemplate.getForEntity(
                "/api/artifacts/locks/joint", JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);

        ResponseEntity<JsonNode> one = restTemplate.getForEntity(
                "/api/artifacts/locks/joint/{id}", JsonNode.class, lockId);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody().path("id").asLong()).isEqualTo(lockId);

        // 联合锁不出现在单根锁查询中；单根端点按 id 查询返回 404。
        ResponseEntity<JsonNode> singleList = restTemplate.getForEntity(
                "/api/artifacts/locks", JsonNode.class);
        assertThat(singleList.getBody()).hasSize(0);
        ResponseEntity<JsonNode> wrongType = restTemplate.getForEntity(
                "/api/artifacts/locks/{id}", JsonNode.class, lockId);
        assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> missing = restTemplate.getForEntity(
                "/api/artifacts/locks/joint/9999", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reorderedRootsWithSameRequestIdReplaySameResponse() {
        register("a", 1, dependency("x", 1, 2));
        register("b", 1, dependency("x", 1, 2));
        register("x", 2);

        String rid = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = postJointLock(
                jointLockBody(3L, root("a", 1), root("b", 1)), rid);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 根集合换序同参：重放首次完整响应。
        ResponseEntity<JsonNode> replay = postJointLock(
                jointLockBody(3L, root("b", 1), root("a", 1)), rid);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("id").asLong())
                .isEqualTo(first.getBody().path("id").asLong());
        assertThat(replay.getBody().path("createdAt").asText())
                .isEqualTo(first.getBody().path("createdAt").asText());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentRootsReturns409() {
        register("a", 1);
        register("b", 1);
        register("c", 1);

        String rid = UUID.randomUUID().toString();
        postJointLock(jointLockBody(3L, root("a", 1), root("b", 1)), rid);
        ResponseEntity<JsonNode> conflict = postJointLock(
                jointLockBody(3L, root("a", 1), root("c", 1)), rid);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().path("error").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void duplicateRootNameReturns400() {
        ResponseEntity<JsonNode> sameVersion = postJointLock(
                jointLockBody(0L, root("a", 1), root("a", 1)), UUID.randomUUID().toString());
        assertThat(sameVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> differentVersion = postJointLock(
                jointLockBody(0L, root("a", 1), root("a", 2)), UUID.randomUUID().toString());
        assertThat(differentVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rootCountOutOfRangeReturns400() {
        ResponseEntity<JsonNode> tooFew = postJointLock(
                jointLockBody(0L, root("a", 1)), UUID.randomUUID().toString());
        assertThat(tooFew.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> tooMany = postJointLock(
                jointLockBody(0L, root("a", 1), root("b", 1), root("c", 1), root("d", 1),
                        root("e", 1)), UUID.randomUUID().toString());
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingRootReturns422() {
        register("a", 1);
        ResponseEntity<JsonNode> response = postJointLock(
                jointLockBody(1L, root("a", 1), root("ghost", 1)), UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void withdrawnSharedCandidateWithoutFallbackReturns422() {
        // 题干合成场景：撤回 x2 且无 x1 时整单失败。
        register("a", 1, dependency("x", 1, 3));
        register("b", 1, dependency("x", 1, 2));
        register("x", 3);
        register("x", 2);
        restTemplate.postForEntity("/api/artifacts/x/versions/2/withdraw",
                new HttpEntity<>("", jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        ResponseEntity<JsonNode> response = postJointLock(
                jointLockBody(5L, root("a", 1), root("b", 1)), UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isZero();
    }

    @Test
    void staleExpectedRepositoryVersionReturns409() {
        register("a", 1);
        register("b", 1);
        ResponseEntity<JsonNode> response = postJointLock(
                jointLockBody(1L, root("a", 1), root("b", 1)), UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void missingRequestIdHeaderReturns400() {
        ResponseEntity<JsonNode> response = postJointLock(
                jointLockBody(0L, root("a", 1), root("b", 1)), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
