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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * peer 声明与带 peer 锁定的真实 HTTP 入口测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PeerDeclarationHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
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

    private ResponseEntity<JsonNode> register(String name, int version, String dependenciesJson) {
        String body = "{\"name\":\"" + name + "\",\"version\":" + version
                + ",\"dependencies\":" + dependenciesJson + "}";
        return restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> declarePeers(String name, int version, String requestId,
                                                  String body) {
        return restTemplate.postForEntity(
                "/api/artifacts/" + name + "/versions/" + version + "/peers",
                new HttpEntity<>(body, jsonHeaders(requestId)), JsonNode.class);
    }

    @Test
    void declarePeersViaHttpReturns201AndReplaysWithSameRequestId() {
        register("p", 1, "[]");
        String rid = UUID.randomUUID().toString();
        String body = "{\"operator\":\"alice\",\"peers\":["
                + "{\"name\":\"h\",\"minimumVersion\":1,\"maximumVersion\":2}]}";

        ResponseEntity<JsonNode> first = declarePeers("p", 1, rid, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().path("operator").asText()).isEqualTo("alice");
        assertThat(first.getBody().path("repositoryVersion").asLong()).isEqualTo(2L);
        assertThat(first.getBody().path("peers")).hasSize(1);
        assertThat(first.getBody().path("peers").get(0).path("name").asText()).isEqualTo("h");

        ResponseEntity<JsonNode> replay = declarePeers("p", 1, rid, body);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("createdAt").asText())
                .isEqualTo(first.getBody().path("createdAt").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(2L);
    }

    @Test
    void declarePeersWithBlankOperatorReturns400() {
        register("p", 1, "[]");
        String body = "{\"operator\":\" \",\"peers\":["
                + "{\"name\":\"h\",\"minimumVersion\":1,\"maximumVersion\":1}]}";
        ResponseEntity<JsonNode> response = declarePeers("p", 1, UUID.randomUUID().toString(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void declarePeersWithMoreThanThreePeersReturns400() {
        register("p", 1, "[]");
        String body = "{\"operator\":\"a\",\"peers\":["
                + "{\"name\":\"h1\",\"minimumVersion\":1,\"maximumVersion\":1},"
                + "{\"name\":\"h2\",\"minimumVersion\":1,\"maximumVersion\":1},"
                + "{\"name\":\"h3\",\"minimumVersion\":1,\"maximumVersion\":1},"
                + "{\"name\":\"h4\",\"minimumVersion\":1,\"maximumVersion\":1}]}";
        ResponseEntity<JsonNode> response = declarePeers("p", 1, UUID.randomUUID().toString(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lockWithPeerConstraintFallsBackToCompatiblePlugin() {
        // 题干合成场景（HTTP 端到端）：根依赖 h[1,1] 与 p[1,2]；p2 要求 h2，p1 要求 h1。
        register("root", 1, "[{\"name\":\"h\",\"minimumVersion\":1,\"maximumVersion\":1},"
                + "{\"name\":\"p\",\"minimumVersion\":1,\"maximumVersion\":2}]");
        register("h", 1, "[]");
        register("p", 1, "[]");
        register("p", 2, "[]");
        declarePeers("p", 2, UUID.randomUUID().toString(),
                "{\"operator\":\"t\",\"peers\":[{\"name\":\"h\",\"minimumVersion\":2,"
                        + "\"maximumVersion\":2}]}");
        declarePeers("p", 1, UUID.randomUUID().toString(),
                "{\"operator\":\"t\",\"peers\":[{\"name\":\"h\",\"minimumVersion\":1,"
                        + "\"maximumVersion\":1}]}");

        String lockBody = "{\"rootName\":\"root\",\"rootVersion\":1,\"expectedRepositoryVersion\":6}";
        ResponseEntity<JsonNode> lock = restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(lockBody, jsonHeaders(UUID.randomUUID().toString())),
                JsonNode.class);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lock.getBody().path("entries")).hasSize(3);
        assertThat(lock.getBody().path("entries").get(0).path("name").asText()).isEqualTo("h");
        assertThat(lock.getBody().path("entries").get(1).path("name").asText()).isEqualTo("p");
        assertThat(lock.getBody().path("entries").get(1).path("version").asInt()).isEqualTo(1);
        assertThat(lock.getBody().path("entries").get(2).path("name").asText()).isEqualTo("root");
    }
}
