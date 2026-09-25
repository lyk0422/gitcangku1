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
 * 镜像源 REST 端点真实 HTTP 测试：登记、可用性切换、明细查询、
 * 锁定固化镜像清单与故障切换查询的状态码与 JSON 结构。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MirrorControllerHttpTest {

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

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private void registerArtifact(String name, int version) {
        String body = "{\"name\":\"" + name + "\",\"version\":" + version + ",\"dependencies\":[]}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/api/artifacts",
                new HttpEntity<>(body, jsonHeaders(rid())), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<JsonNode> registerMirror(String name, int version,
                                                    String mirrorId, int priority) {
        String body = "{\"mirrorId\":\"" + mirrorId + "\",\"priority\":" + priority + "}";
        return restTemplate.postForEntity(
                "/api/artifacts/" + name + "/versions/" + version + "/mirrors",
                new HttpEntity<>(body, jsonHeaders(rid())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> toggle(String name, int version, String mirrorId,
                                            String action) {
        return restTemplate.exchange(
                "/api/artifacts/" + name + "/versions/" + version + "/mirrors/" + mirrorId + "/" + action,
                HttpMethod.POST, new HttpEntity<>("", jsonHeaders(rid())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> lockApp(long expectedVersion) {
        String body = "{\"rootName\":\"app\",\"rootVersion\":1,"
                + "\"expectedRepositoryVersion\":" + expectedVersion + "}";
        return restTemplate.postForEntity("/api/artifacts/locks",
                new HttpEntity<>(body, jsonHeaders(rid())), JsonNode.class);
    }

    @Test
    void registerMirrorReturns201AndDetailQueryShowsRecord() {
        registerArtifact("app", 1);
        ResponseEntity<JsonNode> created = registerMirror("app", 1, "mirror-a", 1);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("mirrorId").asText()).isEqualTo("mirror-a");
        assertThat(created.getBody().path("priority").asInt()).isEqualTo(1);
        assertThat(created.getBody().path("available").asBoolean()).isTrue();
        assertThat(created.getBody().path("repositoryVersion").asLong()).isEqualTo(2L);

        ResponseEntity<JsonNode> details = restTemplate.getForEntity(
                "/api/artifacts/app/versions/1/mirrors", JsonNode.class);
        assertThat(details.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(details.getBody().isArray()).isTrue();
        assertThat(details.getBody()).hasSize(1);
        assertThat(details.getBody().get(0).path("mirrorId").asText()).isEqualTo("mirror-a");
        assertThat(details.getBody().get(0).path("available").asBoolean()).isTrue();
    }

    @Test
    void registerMirrorOnMissingVersionReturns404() {
        ResponseEntity<JsonNode> response = registerMirror("ghost", 9, "m1", 1);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidMirrorRequestReturns400() {
        registerArtifact("app", 1);
        // priority 必须为正整数。
        ResponseEntity<JsonNode> response = registerMirror("app", 1, "m1", 0);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicateMirrorReturns409() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        ResponseEntity<JsonNode> duplicate = registerMirror("app", 1, "m1", 2);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fourthMirrorReturns422() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        registerMirror("app", 1, "m2", 2);
        registerMirror("app", 1, "m3", 3);
        ResponseEntity<JsonNode> fourth = registerMirror("app", 1, "m4", 4);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void unavailableAndAvailableToggleFlow() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);

        ResponseEntity<JsonNode> off = toggle("app", 1, "m1", "unavailable");
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(off.getBody().path("available").asBoolean()).isFalse();

        // 重复标记不可用 → 409。
        ResponseEntity<JsonNode> again = toggle("app", 1, "m1", "unavailable");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<JsonNode> on = toggle("app", 1, "m1", "available");
        assertThat(on.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().path("available").asBoolean()).isTrue();

        // 未登记镜像 → 404。
        ResponseEntity<JsonNode> missing = toggle("app", 1, "ghost", "unavailable");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lockResponseCarriesFrozenMirrorListAndFailoverFollowsAvailability() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        registerMirror("app", 1, "m2", 2);
        // 仓库版本：登记制品(1) + 两个镜像(3)。
        ResponseEntity<JsonNode> lock = lockApp(3L);
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long lockId = lock.getBody().path("id").asLong();
        JsonNode entry = lock.getBody().path("entries").get(0);
        assertThat(entry.path("mirrors")).hasSize(2);
        assertThat(entry.path("mirrors").get(0).path("mirrorId").asText()).isEqualTo("m1");
        assertThat(entry.path("mirrors").get(1).path("mirrorId").asText()).isEqualTo("m2");

        // 故障切换：当前 m1 可用。
        ResponseEntity<JsonNode> failover = restTemplate.getForEntity(
                "/api/artifacts/locks/" + lockId + "/entries/app/failover-mirror", JsonNode.class);
        assertThat(failover.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failover.getBody().path("mirrorId").asText()).isEqualTo("m1");
        assertThat(failover.getBody().path("priority").asInt()).isEqualTo(1);

        // m1 标记不可用后，故障切换回退到 m2，但锁文件固化清单不变。
        toggle("app", 1, "m1", "unavailable");
        ResponseEntity<JsonNode> fallback = restTemplate.getForEntity(
                "/api/artifacts/locks/" + lockId + "/entries/app/failover-mirror", JsonNode.class);
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fallback.getBody().path("mirrorId").asText()).isEqualTo("m2");

        ResponseEntity<JsonNode> reloaded = restTemplate.getForEntity(
                "/api/artifacts/locks/" + lockId, JsonNode.class);
        assertThat(reloaded.getBody().path("entries").get(0).path("mirrors")).hasSize(2);

        // 全部不可用 → 422 并说明。
        toggle("app", 1, "m2", "unavailable");
        ResponseEntity<JsonNode> none = restTemplate.getForEntity(
                "/api/artifacts/locks/" + lockId + "/entries/app/failover-mirror", JsonNode.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(none.getBody().path("message").asText()).contains("没有可用镜像源");
    }

    @Test
    void failoverForUnknownNameReturns404AndUnknownLockReturns404() {
        registerArtifact("app", 1);
        ResponseEntity<JsonNode> lock = lockApp(1L);
        long lockId = lock.getBody().path("id").asLong();

        ResponseEntity<JsonNode> unknownName = restTemplate.getForEntity(
                "/api/artifacts/locks/" + lockId + "/entries/ghost/failover-mirror", JsonNode.class);
        assertThat(unknownName.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> unknownLock = restTemplate.getForEntity(
                "/api/artifacts/locks/9999/entries/app/failover-mirror", JsonNode.class);
        assertThat(unknownLock.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void mirrorWriteOperationsAreIdempotentByRequestId() {
        registerArtifact("app", 1);
        String rid = rid();
        String body = "{\"mirrorId\":\"m1\",\"priority\":1}";
        HttpEntity<String> request = new HttpEntity<>(body, jsonHeaders(rid));

        ResponseEntity<JsonNode> first = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/mirrors", request, JsonNode.class);
        ResponseEntity<JsonNode> replay = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/mirrors", request, JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("createdAt").asText())
                .isEqualTo(first.getBody().path("createdAt").asText());

        // 同键异参 → 409。
        ResponseEntity<JsonNode> conflict = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/mirrors",
                new HttpEntity<>("{\"mirrorId\":\"m2\",\"priority\":2}", jsonHeaders(rid)),
                JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_mirror", Integer.class)).isEqualTo(1);
    }

    @Test
    void detailQueryOnMissingVersionReturns404() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/api/artifacts/ghost/versions/1/mirrors", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingRequestIdOnMirrorRegisterReturns400() {
        registerArtifact("app", 1);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/api/artifacts/app/versions/1/mirrors",
                new HttpEntity<>("{\"mirrorId\":\"m1\",\"priority\":1}", jsonHeaders(null)),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
