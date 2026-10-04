package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 排练执行链路测试（真实H2）：顺序回报、跳号409、失败不推进、幂等重放、
 * 重绑最新版本、快照隔离与结束判定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionExecutionFlowTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cue_failure");
        jdbc.update("DELETE FROM cue_success");
        jdbc.update("DELETE FROM session_cue");
        jdbc.update("DELETE FROM rehearsal_session");
        jdbc.update("DELETE FROM cue_item");
        jdbc.update("DELETE FROM cue_version");
    }

    private static Map<String, Object> cue(int seq, int duration) {
        return Map.of("seqNo", seq, "cueType", "SOUND",
                "triggerPhrase", "口令" + seq, "durationSeconds", duration);
    }

    private long frozenVersion(String play, List<Map<String, Object>> cues) {
        ResponseEntity<JsonNode> created = rest.postForEntity(
                "/api/plays/{play}/cue-versions", Map.of("cues", cues), JsonNode.class, play);
        long id = created.getBody().get("id").asLong();
        ResponseEntity<JsonNode> frozen = rest.postForEntity(
                "/api/cue-versions/{id}/freeze", null, JsonNode.class, id);
        assertThat(frozen.getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }

    private long startedSession(long versionId, String name) {
        ResponseEntity<JsonNode> created = rest.postForEntity("/api/sessions",
                Map.of("sessionName", name, "versionId", versionId), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = created.getBody().get("id").asLong();
        ResponseEntity<JsonNode> started = rest.postForEntity(
                "/api/sessions/{id}/start", null, JsonNode.class, id);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }

    private ResponseEntity<JsonNode> report(long sessionId, String requestId, int seq,
                                            boolean success, String reason, String operator) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("seqNo", seq);
        body.put("success", success);
        body.put("failReason", reason);
        body.put("operator", operator);
        return rest.postForEntity("/api/sessions/{id}/reports", body, JsonNode.class, sessionId);
    }

    @Test
    void orderedReportingIdempotencyAndFinish() {
        long versionId = frozenVersion("P1", List.of(cue(1, 30), cue(2, 45), cue(3, 20)));
        long sessionId = startedSession(versionId, "场次A");

        // 跳号回报2被拒（顺序1、2、3须先回报1）
        ResponseEntity<JsonNode> skipped = report(sessionId, "r-skip", 2, true, null, "op1");
        assertThat(skipped.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 回报1成功，同requestId同参重放原回报，异参409
        assertThat(report(sessionId, "r1", 1, true, null, "op1").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> replay = report(sessionId, "r1", 1, true, null, "op1");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("replayed").asBoolean()).isTrue();
        assertThat(report(sessionId, "r1", 2, true, null, "op1").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cue_success WHERE session_id = ?",
                Integer.class, sessionId)).isEqualTo(1);

        // 失败回报必须带原因；记录原因但不推进已执行序号
        assertThat(report(sessionId, "r2", 2, false, null, "op1").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<JsonNode> failed = report(sessionId, "r2", 2, false, "音响未就绪", "op1");
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failed.getBody().get("result").asText()).isEqualTo("FAILED");
        JsonNode afterFail = rest.getForObject("/api/sessions/{id}", JsonNode.class, sessionId);
        assertThat(afterFail.get("session").get("lastExecutedSeq").asInt()).isEqualTo(1);
        assertThat(afterFail.get("cues").get(1).get("result").asText()).isEqualTo("FAILED");
        assertThat(afterFail.get("cues").get(1).get("failReason").asText()).isEqualTo("音响未就绪");

        // 失败不消耗幂等键：同一requestId可再次用于成功回报
        ResponseEntity<JsonNode> ok2 = report(sessionId, "r2", 2, true, null, "op2");
        assertThat(ok2.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 未全部执行不能结束；补齐后结束；结束后拒绝再回报
        assertThat(rest.postForEntity("/api/sessions/{id}/finish", null, JsonNode.class, sessionId)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(report(sessionId, "r3", 3, true, null, "op1").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> finished = rest.postForEntity(
                "/api/sessions/{id}/finish", null, JsonNode.class, sessionId);
        assertThat(finished.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finished.getBody().get("status").asText()).isEqualTo("FINISHED");
        assertThat(report(sessionId, "r4", 3, true, null, "op1").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode detail = rest.getForObject("/api/sessions/{id}", JsonNode.class, sessionId);
        assertThat(detail.get("cues")).hasSize(3);
        assertThat(detail.get("cues").get(2).get("result").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void rebindToLatestVersionAndSnapshotIsolation() {
        long v1 = frozenVersion("P1", List.of(cue(1, 30), cue(2, 45)));
        ResponseEntity<JsonNode> created = rest.postForEntity("/api/sessions",
                Map.of("sessionName", "场次B", "versionId", v1), JsonNode.class);
        long sessionId = created.getBody().get("id").asLong();

        // 未开始场次重绑到最新冻结版本
        long v2 = frozenVersion("P1", List.of(cue(1, 30), cue(2, 50), cue(3, 10)));
        ResponseEntity<JsonNode> rebound = rest.postForEntity(
                "/api/sessions/{id}/rebind", null, JsonNode.class, sessionId);
        assertThat(rebound.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rebound.getBody().get("versionId").asLong()).isEqualTo(v2);

        // 开始后冻结快照，之后的新版本不影响本场
        rest.postForEntity("/api/sessions/{id}/start", null, JsonNode.class, sessionId);
        long v3 = frozenVersion("P1", List.of(cue(1, 99)));
        assertThat(rest.postForEntity("/api/sessions/{id}/rebind", null, JsonNode.class, sessionId)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode detail = rest.getForObject("/api/sessions/{id}", JsonNode.class, sessionId);
        assertThat(detail.get("session").get("versionId").asLong()).isEqualTo(v2);
        assertThat(detail.get("cues")).hasSize(3);
        assertThat(detail.get("cues").get(1).get("durationSeconds").asInt()).isEqualTo(50);
        assertThat(v3).isNotEqualTo(v2);
    }
}