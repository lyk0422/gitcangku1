package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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
 * 并发测试（真实H2、真实线程协调）：两个操作员同时回报同一提示点只留一条成功记录；
 * 下一提示点执行与本场结束并发时，按已提交序号与完整性决定能否结束。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentReportTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
        jdbc.update("DELETE FROM cue_failure");
        jdbc.update("DELETE FROM cue_success");
        jdbc.update("DELETE FROM session_cue");
        jdbc.update("DELETE FROM rehearsal_session");
        jdbc.update("DELETE FROM cue_item");
        jdbc.update("DELETE FROM cue_version");
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private long startedSession(int... durations) {
        List<Map<String, Object>> cues = java.util.stream.IntStream.range(0, durations.length)
                .mapToObj(i -> Map.<String, Object>of("seqNo", i + 1, "cueType", "LIGHT",
                        "triggerPhrase", "口令" + (i + 1), "durationSeconds", durations[i]))
                .toList();
        JsonNode version = rest.postForEntity("/api/plays/P/cue-versions",
                Map.of("cues", cues), JsonNode.class).getBody();
        long versionId = version.get("id").asLong();
        rest.postForEntity("/api/cue-versions/{id}/freeze", null, JsonNode.class, versionId);
        JsonNode session = rest.postForEntity("/api/sessions",
                Map.of("sessionName", "并发场", "versionId", versionId), JsonNode.class).getBody();
        long sessionId = session.get("id").asLong();
        rest.postForEntity("/api/sessions/{id}/start", null, JsonNode.class, sessionId);
        return sessionId;
    }

    private ResponseEntity<JsonNode> report(long sessionId, String requestId, int seq, String operator) {
        return rest.postForEntity("/api/sessions/{id}/reports",
                Map.of("requestId", requestId, "seqNo", seq, "success", true, "operator", operator),
                JsonNode.class, sessionId);
    }

    @Test
    void concurrentSameCueLeavesExactlyOneSuccess() throws Exception {
        long sessionId = startedSession(30, 45, 20);
        CountDownLatch gate = new CountDownLatch(1);

        Future<ResponseEntity<JsonNode>> a = pool.submit(() -> {
            gate.await();
            return report(sessionId, "req-a", 1, "opA");
        });
        Future<ResponseEntity<JsonNode>> b = pool.submit(() -> {
            gate.await();
            return report(sessionId, "req-b", 1, "opB");
        });
        gate.countDown();
        ResponseEntity<JsonNode> ra = a.get(15, TimeUnit.SECONDS);
        ResponseEntity<JsonNode> rb = b.get(15, TimeUnit.SECONDS);

        int ok = (ra.getStatusCode() == HttpStatus.OK ? 1 : 0) + (rb.getStatusCode() == HttpStatus.OK ? 1 : 0);
        int conflict = (ra.getStatusCode() == HttpStatus.CONFLICT ? 1 : 0)
                + (rb.getStatusCode() == HttpStatus.CONFLICT ? 1 : 0);
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cue_success WHERE session_id = ? AND seq_no = 1",
                Integer.class, sessionId)).isEqualTo(1);
        JsonNode detail = rest.getForObject("/api/sessions/{id}", JsonNode.class, sessionId);
        assertThat(detail.get("session").get("lastExecutedSeq").asInt()).isEqualTo(1);
    }

    @Test
    void lastCueReportAndFinishRaceStaysConsistent() throws Exception {
        long sessionId = startedSession(30, 45);
        assertThat(report(sessionId, "req-1", 1, "opA").getStatusCode()).isEqualTo(HttpStatus.OK);
        CountDownLatch gate = new CountDownLatch(1);

        Future<ResponseEntity<JsonNode>> reporter = pool.submit(() -> {
            gate.await();
            return report(sessionId, "req-2", 2, "opB");
        });
        Future<ResponseEntity<JsonNode>> finisher = pool.submit(() -> {
            gate.await();
            return rest.postForEntity("/api/sessions/{id}/finish", null, JsonNode.class, sessionId);
        });
        gate.countDown();
        ResponseEntity<JsonNode> reportResp = reporter.get(15, TimeUnit.SECONDS);
        ResponseEntity<JsonNode> finishResp = finisher.get(15, TimeUnit.SECONDS);

        // 最后一条回报始终成功
        assertThat(reportResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cue_success WHERE session_id = ?",
                Integer.class, sessionId)).isEqualTo(2);

        JsonNode detail = rest.getForObject("/api/sessions/{id}", JsonNode.class, sessionId);
        String status = detail.get("session").get("status").asText();
        if (finishResp.getStatusCode() == HttpStatus.OK) {
            // 结束发生在回报提交之后：场次必须完整且为FINISHED
            assertThat(status).isEqualTo("FINISHED");
            assertThat(detail.get("session").get("lastExecutedSeq").asInt()).isEqualTo(2);
        } else {
            // 结束判定先于回报提交：完整性不足被拒，场次保持IN_PROGRESS且可再次结束
            assertThat(finishResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(status).isEqualTo("IN_PROGRESS");
            ResponseEntity<JsonNode> retry = rest.postForEntity(
                    "/api/sessions/{id}/finish", null, JsonNode.class, sessionId);
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(retry.getBody().get("status").asText()).isEqualTo("FINISHED");
        }
    }
}