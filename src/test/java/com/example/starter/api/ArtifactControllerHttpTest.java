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
 * 真实 HTTP 入口测试：状态码、X-Request-Id 头、JSON 结构、operator 透传与幂等重放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArtifactControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_peer_match");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_peer");
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

    private String registerBody(String name, int version, String operator, Object... dependencies) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("operator", operator);
        body.put("dependencies", List.of(dependencies));
        body.put("peers", List.of());
        return toJson(body);
    }

    private String registerBodyWithPeers(String name, int version, String operator,
                                         List<Object> dependencies, List<Object> peers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        body.put("operator", operator);
        body.put("dependencies", dependencies);
        body.put("peers", peers);
        return toJson(body);
    }

    private Map<String, Object> range(String name, int min, int max) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("name", name);
        dep.put("minimumVersion", min);
        dep.put("maximumVersion", max);
        return dep;
    }

    private String withdrawBody(String operator, long expectedRepositoryVersion) {
        return toJson(Map.of("operator", operator,
                "expectedRepositoryVersion", expectedRepositoryVersion));
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
        String body = registerBody("app", 1, "alice", range("lib", 1, 2));
        HttpEntity<String> request = new HttpEntity<>(body, jsonHeaders(rid));

        ResponseEntity<JsonNode> first = restTemplate.postForEntity(
                "/api/artifacts", request, JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().path("name").asText()).isEqualTo("app");
        assertThat(first.getBody().path("version").asInt()).isEqualTo(1);
        assertThat(first.getBody().path("operator").asText()).isEqualTo("alice");
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
                new HttpEntity<>(registerBody("app", 1, "alice"), jsonHeaders(rid)), JsonNode.class);

        ResponseEntity<JsonNode> conflict = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 2, "alice"), jsonHeaders(rid)), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().path("error").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void missingRequestIdHeaderReturns400() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1, "alice"), jsonHeaders(null)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankOperatorReturns400() {
        Map<String, Object> body = Map.of("name", "app", "version", 1, "operator", " ",
                "dependencies", List.of(), "peers", List.of());
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidBeanReturns400() {
        Map<String, Object> body = Map.of("name", "app", "version", 0, "operator", "alice",
                "dependencies", List.of(), "peers", List.of());
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(toJson(body), jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void fullLockWorkflowAndHistoryQueries() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1, "alice", range("lib", 1, 2)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("lib", 1, "alice"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("lib", 2, "alice"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"operator\":\"dave\","
                + "\"expectedRepositoryVersion\":3}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long lockId = lock.getBody().path("id").asLong();
        assertThat(lock.getBody().path("operator").asText()).isEqualTo("dave");
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
    void peerScenarioBacktracksAndFreezesHostMatch() {
        // 合成：root 普通依赖 H1 与 P[1,2]；P2 peer H2，P1 peer H1 → 锁定回退 P1。
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBodyWithPeers("root", 1, "alice",
                        List.of(range("h", 1, 1), range("p", 1, 2)), List.of()),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("h", 1, "alice"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBodyWithPeers("p", 2, "alice", List.of(),
                        List.of(range("h", 2, 2))),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBodyWithPeers("p", 1, "alice", List.of(),
                        List.of(range("h", 1, 1))),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        String lockBody = "{\"rootName\":\"root\",\"rootVersion\":1,\"operator\":\"dave\","
                + "\"expectedRepositoryVersion\":4}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode entries = lock.getBody().path("entries");
        assertThat(entries).hasSize(3);
        assertThat(entries.get(1).path("name").asText()).isEqualTo("p");
        assertThat(entries.get(1).path("version").asInt()).isEqualTo(1);

        JsonNode peerMatches = lock.getBody().path("peerMatches");
        assertThat(peerMatches).hasSize(1);
        assertThat(peerMatches.get(0).path("declarerName").asText()).isEqualTo("p");
        assertThat(peerMatches.get(0).path("declarerVersion").asInt()).isEqualTo(1);
        assertThat(peerMatches.get(0).path("hostName").asText()).isEqualTo("h");
        assertThat(peerMatches.get(0).path("matchedVersion").asInt()).isEqualTo(1);
    }

    @Test
    void peerWithoutReachableHostReturns422() {
        // root 只普通依赖 P；P peer H，但无 H 普通边 → peer 不拉宿主，422。
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBodyWithPeers("root", 1, "alice",
                        List.of(range("p", 1, 1)), List.of()),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("h", 1, "alice"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBodyWithPeers("p", 1, "alice", List.of(),
                        List.of(range("h", 1, 2))),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        String lockBody = "{\"rootName\":\"root\",\"rootVersion\":1,\"operator\":\"dave\","
                + "\"expectedRepositoryVersion\":3}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void staleRepositoryVersionReturns409() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1, "alice"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"operator\":\"dave\","
                + "\"expectedRepositoryVersion\":0}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void infeasibleLockReturns422() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1, "alice", range("lib", 2, 2)),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("lib", 1, "alice"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"operator\":\"dave\","
                + "\"expectedRepositoryVersion\":2}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void withdrawWorkflowChecksExpectedVersionAndAdvances() {
        restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(registerBody("app", 1, "alice"),
                        jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);

        String rid = UUID.randomUUID().toString();
        // 期望版本错误（实际为 1）→ 409。
        ResponseEntity<JsonNode> stale = restTemplate.exchange(
                "/api/artifacts/app/versions/1/withdraw",
                HttpMethod.POST,
                new HttpEntity<>(withdrawBody("eve", 0L), jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<JsonNode> withdrawn = restTemplate.exchange(
                "/api/artifacts/app/versions/1/withdraw",
                HttpMethod.POST,
                new HttpEntity<>(withdrawBody("eve", 1L), jsonHeaders(rid)),
                JsonNode.class);
        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(withdrawn.getBody().path("withdrawn").asBoolean()).isTrue();
        assertThat(withdrawn.getBody().path("operator").asText()).isEqualTo("eve");
        assertThat(withdrawn.getBody().path("repositoryVersion").asLong()).isEqualTo(2L);

        // 撤回后锁定撤回的根 → 409。
        String lockBody = "{\"rootName\":\"app\",\"rootVersion\":1,\"operator\":\"dave\","
                + "\"expectedRepositoryVersion\":2}";
        ResponseEntity<JsonNode> lockOnWithdrawn = restTemplate.postForEntity(
                "/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lockOnWithdrawn.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
