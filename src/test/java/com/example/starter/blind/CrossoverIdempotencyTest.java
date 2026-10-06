package com.example.starter.blind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 交叉阶段写操作的幂等与版本边界：
 * 同键同参重放首次完整响应（最初阶段与时刻，不延长洗脱、不重新启动）；
 * 异参 409；失败不占键；期望版本不匹配 409；权限校验先于幂等回放。
 * 揭盲仍按原申请人与 REVIEWER 审批规则，阶段接口不泄露处理代码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossoverIdempotencyTest extends AbstractBlindIntegrationTest {

    private static final long T0 = 1_700_000_000_000L;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Actor-Id", actor);
        h.set("X-Role", role);
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> post(String path, HttpHeaders headers, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> phase(String expId, String pid, String action,
                                         String requestId, String body) {
        return post("/api/experiments/" + expId + "/participants/" + pid + "/phases/" + action,
                headers("c1", "COORDINATOR", requestId), body);
    }

    private void setup(String expId, String pid, String prefix) {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", prefix + "-create"),
                "{\"blockCount\":2,\"mode\":\"CROSSOVER\",\"washoutMinutes\":5}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", prefix + "-alloc"), null).getStatusCode().value());
    }

    @Test
    void replaySameKey_returnsOriginalPhaseAndMoment_noRestartNoWashoutExtension() throws Exception {
        setup("XI-1", "P1", "xi1");
        clock.setTime(T0);
        ResponseEntity<String> first = phase("XI-1", "P1", "first/start", "xi1-s1", null);
        assertEquals(200, first.getStatusCode().value());
        JsonNode firstBody = mapper.readTree(first.getBody());
        assertEquals(T0, firstBody.path("firstStartedAt").asLong());

        // 时钟前进后同键同参重放：仍返回最初阶段与时刻，不重新启动
        clock.advance(120_000L);
        ResponseEntity<String> replay = phase("XI-1", "P1", "first/start", "xi1-s1", null);
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());

        // 结束第一阶段后同键重放：仍返回首次 FIRST_RUNNING 响应，不延长洗脱
        clock.setTime(T0 + 60_000L);
        assertEquals(200, phase("XI-1", "P1", "first/end", "xi1-e1", null)
                .getStatusCode().value());
        ResponseEntity<String> replayAfterEnd = phase("XI-1", "P1", "first/start", "xi1-s1", null);
        assertEquals(200, replayAfterEnd.getStatusCode().value());
        assertEquals(first.getBody(), replayAfterEnd.getBody());
        JsonNode state = mapper.readTree(rest.exchange(
                "/api/experiments/XI-1/participants/P1/phases", HttpMethod.GET,
                new HttpEntity<>(null, headers("c1", "COORDINATOR", null)), String.class)
                .getBody());
        assertEquals("WASHOUT", state.path("status").asText());
        assertEquals(T0 + 60_000L, state.path("firstEndedAt").asLong());
        assertEquals(T0, state.path("firstStartedAt").asLong());
    }

    @Test
    void sameKeyDifferentParams_conflict409_andFailureDoesNotConsumeKey() {
        setup("XI-2", "P1", "xi2");
        assertEquals(200, phase("XI-2", "P1", "first/start", "xi2-s1", null)
                .getStatusCode().value());
        // 同键异参（带期望版本）：409
        assertEquals(409, phase("XI-2", "P1", "first/start", "xi2-s1", "{\"expectedVersion\":0}")
                .getStatusCode().value());
        // 同键异操作：409
        assertEquals(409, phase("XI-2", "P1", "first/end", "xi2-s1", null)
                .getStatusCode().value());
        // 权限先于幂等回放：REVIEWER 持同键 403 而非回放
        assertEquals(403, post("/api/experiments/XI-2/participants/P1/phases/first/end",
                headers("c1", "REVIEWER", "xi2-s1"), null).getStatusCode().value());

        // 失败不占键：洗脱不足 422 后，同键在洗脱满时可成功
        assertEquals(200, phase("XI-2", "P1", "first/end", "xi2-e1", null)
                .getStatusCode().value());
        assertEquals(422, phase("XI-2", "P1", "second/start", "xi2-s2", null)
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'xi2-s2'",
                Integer.class));
        clock.advance(5 * 60_000L);
        assertEquals(200, phase("XI-2", "P1", "second/start", "xi2-s2", null)
                .getStatusCode().value());
    }

    @Test
    void expectedVersion_optimisticConcurrency() throws Exception {
        setup("XI-3", "P1", "xi3");
        // 期望版本不匹配：409
        assertEquals(409, phase("XI-3", "P1", "first/start", "xi3-bad", "{\"expectedVersion\":3}")
                .getStatusCode().value());
        // 匹配当前版本 0：成功，版本升至 1
        ResponseEntity<String> started = phase("XI-3", "P1", "first/start", "xi3-s1",
                "{\"expectedVersion\":0}");
        assertEquals(200, started.getStatusCode().value());
        assertEquals(1, mapper.readTree(started.getBody()).path("version").asInt());
        // 旧版本结束：409；当前版本：成功
        assertEquals(409, phase("XI-3", "P1", "first/end", "xi3-e1-bad", "{\"expectedVersion\":0}")
                .getStatusCode().value());
        ResponseEntity<String> ended = phase("XI-3", "P1", "first/end", "xi3-e1",
                "{\"expectedVersion\":1}");
        assertEquals(200, ended.getStatusCode().value());
        assertEquals(2, mapper.readTree(ended.getBody()).path("version").asInt());
    }

    @Test
    void unblindUnchanged_phaseEndpointsNeverLeakTreatment() throws Exception {
        setup("XI-4", "P1", "xi4");
        assertEquals(200, phase("XI-4", "P1", "first/start", "xi4-s1", null)
                .getStatusCode().value());

        // 原受控揭盲：申请 -> 另一名 REVIEWER 批准 -> 申请人查结果
        ResponseEntity<String> apply = post(
                "/api/experiments/XI-4/participants/P1/unblind-requests",
                headers("c1", "COORDINATOR", "xi4-ub-apply"), "{\"reason\":\"合成核查\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = mapper.readTree(apply.getBody()).path("requestId").asText();
        assertEquals(200, post("/api/unblind-requests/" + ubId + "/approval",
                headers("r1", "REVIEWER", "xi4-ub-approve"), null).getStatusCode().value());
        ResponseEntity<String> result = rest.exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                new HttpEntity<>(null, headers("c1", "COORDINATOR", null)), String.class);
        assertEquals(200, result.getStatusCode().value());
        String treatment = mapper.readTree(result.getBody()).path("treatment").asText();
        String dbTreatment = jdbc.queryForObject(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'XI-4' AND a.participant_id = 'P1'",
                String.class);
        assertEquals(dbTreatment, treatment, "揭盲结果应为第一阶段原隐藏代码");

        // 阶段查询/历史不含处理代码与席位号
        ResponseEntity<String> phases = rest.exchange(
                "/api/experiments/XI-4/participants/P1/phases", HttpMethod.GET,
                new HttpEntity<>(null, headers("c1", "COORDINATOR", null)), String.class);
        assertEquals(200, phases.getStatusCode().value());
        assertNotNull(phases.getBody());
        assertFalse(phases.getBody().contains("treatment"));
        assertFalse(phases.getBody().contains("seatNo"));
    }
}
