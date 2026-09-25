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
 * 许可证告知与发布门禁的真实 HTTP 入口测试：
 * 状态码、错误体可区分原因、幂等重放与快照查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LicenseControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM publish_snapshot_entry");
        jdbcTemplate.update("DELETE FROM publish_snapshot_lock");
        jdbcTemplate.update("DELETE FROM publish_snapshot");
        jdbcTemplate.update("DELETE FROM license_policy");
        jdbcTemplate.update("DELETE FROM notice_text");
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

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<JsonNode> postJson(String url, Object body, String requestId) {
        return restTemplate.postForEntity(url,
                new HttpEntity<>(toJson(body), jsonHeaders(requestId)), JsonNode.class);
    }

    /** 构造 app:1 -> lib:1 并锁定，返回锁文件 ID。 */
    private long createLock() {
        postJson("/api/artifacts", Map.of("name", "app", "version", 1,
                "dependencies", List.of(Map.of("name", "lib", "minimumVersion", 1, "maximumVersion", 1))), rid());
        postJson("/api/artifacts", Map.of("name", "lib", "version", 1,
                "dependencies", List.of()), rid());
        ResponseEntity<JsonNode> lock = postJson("/api/artifacts/locks",
                Map.of("rootName", "app", "rootVersion", 1, "expectedRepositoryVersion", 2), rid());
        assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return lock.getBody().path("id").asLong();
    }

    private void approvedText(String textKey, int version, List<String> regions) {
        ResponseEntity<JsonNode> created = postJson("/api/notice-texts",
                Map.of("textKey", textKey, "version", version, "content", "合成告知文本",
                        "regions", regions), rid());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> approved = postJson(
                "/api/notice-texts/" + textKey + "/versions/" + version + "/approve",
                Map.of(), rid());
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void noticeTextLifecycleViaHttp() {
        ResponseEntity<JsonNode> created = postJson("/api/notice-texts",
                Map.of("textKey", "t", "version", 1, "content", "内容", "regions", List.of("cn", "US")),
                rid());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.getBody().path("regions").toString()).contains("CN", "US");

        ResponseEntity<JsonNode> coverage = restTemplate.getForEntity(
                "/api/notice-texts/t/versions/1/coverage", JsonNode.class);
        assertThat(coverage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(coverage.getBody().path("status").asText()).isEqualTo("DRAFT");

        ResponseEntity<JsonNode> approved = postJson("/api/notice-texts/t/versions/1/approve",
                Map.of(), rid());
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().path("status").asText()).isEqualTo("APPROVED");

        // 重复批准 409，错误码可区分。
        ResponseEntity<JsonNode> reapprove = postJson("/api/notice-texts/t/versions/1/approve",
                Map.of(), rid());
        assertThat(reapprove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reapprove.getBody().path("error").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void publishFlowViaHttpAndSnapshotQuery() {
        long lockId = createLock();
        approvedText("t", 1, List.of("CN", "US"));
        ResponseEntity<JsonNode> policy = postJson("/api/license-policies",
                Map.of("scopeType", "ARTIFACT", "artifactName", "lib",
                        "noticeType", "NOTICE_REQUIRED", "textKey", "t", "textVersion", 1), rid());
        assertThat(policy.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> publishBody = Map.of("noticeKey", "release-1",
                "lockFileIds", List.of(lockId), "targetRegions", List.of("CN"));
        ResponseEntity<JsonNode> published = postJson("/api/lock-publishes", publishBody, null);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long snapshotId = published.getBody().path("snapshotId").asLong();
        assertThat(published.getBody().path("locks").get(0).path("lockFileId").asLong())
                .isEqualTo(lockId);

        // 同键同参重放首次完整响应。
        ResponseEntity<JsonNode> replay = postJson("/api/lock-publishes", publishBody, null);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("snapshotId").asLong()).isEqualTo(snapshotId);

        // 异参 409。
        ResponseEntity<JsonNode> conflict = postJson("/api/lock-publishes",
                Map.of("noticeKey", "release-1", "lockFileIds", List.of(lockId),
                        "targetRegions", List.of("US")), null);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 历史快照查询。
        ResponseEntity<JsonNode> snapshot = restTemplate.getForEntity(
                "/api/lock-publishes/" + snapshotId, JsonNode.class);
        assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshot.getBody().path("noticeKey").asText()).isEqualTo("release-1");
        JsonNode libEntry = null;
        for (JsonNode entry : snapshot.getBody().path("locks").get(0).path("entries")) {
            if (entry.path("artifactName").asText().equals("lib")) {
                libEntry = entry;
            }
        }
        assertThat(libEntry).isNotNull();
        assertThat(libEntry.path("textKey").asText()).isEqualTo("t");
        assertThat(libEntry.path("hitPath").asText()).isEqualTo("app:1>lib:1");
    }

    @Test
    void publishViolationReturns422WithDistinguishableReasonAndHitPath() {
        long lockId = createLock();
        // 策略未绑定文本 -> MISSING_NOTICE。
        postJson("/api/license-policies",
                Map.of("scopeType", "ARTIFACT", "artifactName", "lib",
                        "noticeType", "NOTICE_REQUIRED"), rid());

        ResponseEntity<JsonNode> denied = postJson("/api/lock-publishes",
                Map.of("noticeKey", "release-9", "lockFileIds", List.of(lockId),
                        "targetRegions", List.of("CN")), null);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(denied.getBody().path("error").asText()).isEqualTo("MISSING_NOTICE");
        assertThat(denied.getBody().path("message").asText()).contains("app:1>lib:1");

        // 失败不占键：修正数据后同键可成功。
        approvedText("t", 1, List.of("CN"));
        // 原策略未绑定，改由新登记的锁定图作用域策略提供绑定不满足（策略不可改），
        // 因此换用新锁定图验证失败不占键。
        jdbcTemplate.update("DELETE FROM license_policy");
        postJson("/api/license-policies",
                Map.of("scopeType", "ARTIFACT", "artifactName", "lib",
                        "noticeType", "NOTICE_REQUIRED", "textKey", "t", "textVersion", 1), rid());
        ResponseEntity<JsonNode> retried = postJson("/api/lock-publishes",
                Map.of("noticeKey", "release-9", "lockFileIds", List.of(lockId),
                        "targetRegions", List.of("CN")), null);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void hitsAndMissingQueriesViaHttp() {
        long lockId = createLock();
        approvedText("t", 1, List.of("CN"));
        postJson("/api/license-policies",
                Map.of("scopeType", "ARTIFACT", "artifactName", "lib",
                        "noticeType", "NOTICE_REQUIRED", "textKey", "t", "textVersion", 1), rid());

        ResponseEntity<JsonNode> hits = restTemplate.getForEntity(
                "/api/lock-notices/" + lockId + "/hits", JsonNode.class);
        assertThat(hits.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hits.getBody().get(0).path("artifactName").asText()).isEqualTo("lib");
        assertThat(hits.getBody().get(0).path("hitPath").asText()).isEqualTo("app:1>lib:1");

        ResponseEntity<JsonNode> missing = restTemplate.getForEntity(
                "/api/lock-notices/" + lockId + "/missing?regions=CN,US", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(missing.getBody().get(0).path("reason").asText())
                .isEqualTo("NOTICE_REGION_NOT_COVERED");

        ResponseEntity<JsonNode> none = restTemplate.getForEntity(
                "/api/lock-notices/" + lockId + "/missing?regions=CN", JsonNode.class);
        assertThat(none.getBody().isEmpty()).isTrue();
    }

    @Test
    void publishMissingLockReturns404AndMissingNoticeKeyReturns400() {
        ResponseEntity<JsonNode> notFound = postJson("/api/lock-publishes",
                Map.of("noticeKey", "k", "lockFileIds", List.of(999L),
                        "targetRegions", List.of("CN")), null);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> badRequest = restTemplate.postForEntity("/api/lock-publishes",
                new HttpEntity<>("{\"lockFileIds\":[1],\"targetRegions\":[\"CN\"]}",
                        jsonHeaders(null)), JsonNode.class);
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
