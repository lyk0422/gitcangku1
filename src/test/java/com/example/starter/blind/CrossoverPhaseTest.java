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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交叉实验阶段主流程与失败分支（真实 H2 事务）：
 * 状态链 NOT_STARTED→FIRST_RUNNING→WASHOUT→SECOND_RUNNING→DONE；
 * 合成场景：第一阶段 10:00 结束、洗脱 5 分钟，10:04:59 开始第二期拒绝（422），10:05 可开始；
 * 未开始不能结束、重复开始/结束 409、退组 410、关闭禁止新开始但已开始可结束。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossoverPhaseTest extends AbstractBlindIntegrationTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;

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

    private ResponseEntity<String> exchange(String path, HttpMethod method,
                                            HttpHeaders headers, String body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private void createCrossover(String id, int washoutMinutes, String requestId) {
        ResponseEntity<String> resp = exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":2,\"washoutMinutes\":" + washoutMinutes + "}");
        assertEquals(201, resp.getStatusCode().value());
    }

    private void register(String expId, String pid, String requestId) {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null);
        assertEquals(201, resp.getStatusCode().value());
    }

    private ResponseEntity<String> phase(String expId, String pid, int phase, String action,
                                         String requestId, String body) {
        return exchange("/api/experiments/" + expId + "/participants/" + pid
                        + "/phases/" + phase + "/" + action,
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), body);
    }

    @Test
    void fullChain_stateTransitionsAndWashoutBoundary() throws Exception {
        createCrossover("EXP-C1", 5, "req-c1-create");
        register("EXP-C1", "P1", "req-c1-alloc");

        // 初始：未开始任何阶段
        ResponseEntity<String> initial = exchange(
                "/api/experiments/EXP-C1/participants/P1/phases", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, initial.getStatusCode().value());
        JsonNode init = json(initial);
        assertEquals("NOT_STARTED", init.path("phaseState").asText());
        assertEquals(5, init.path("washoutMinutes").asInt());
        assertEquals(0, init.path("version").asInt());
        assertTrue(init.path("firstStartedAt").isNull());
        assertFalse(init.has("treatment"), "阶段视图不得包含处理代码");
        assertFalse(init.has("seatNo"), "阶段视图不得包含席位序号");

        // 开始第一阶段（T0）
        ResponseEntity<String> s1 = phase("EXP-C1", "P1", 1, "start", "req-c1-s1", null);
        assertEquals(200, s1.getStatusCode().value());
        JsonNode s1Body = json(s1);
        assertEquals("FIRST_RUNNING", s1Body.path("phaseState").asText());
        assertEquals(T0, s1Body.path("firstStartedAt").asLong());
        assertEquals(1, s1Body.path("version").asInt());
        assertTrue(s1Body.path("blindCode").asText().matches("[A-Z2-9]{12}"));
        assertFalse(s1Body.has("treatment"));
        assertFalse(s1Body.has("seatNo"));

        // 第一阶段 10:00 结束（T0 + 30 分钟）
        clock.advance(30 * MINUTE);
        ResponseEntity<String> e1 = phase("EXP-C1", "P1", 1, "end", "req-c1-e1", null);
        assertEquals(200, e1.getStatusCode().value());
        JsonNode e1Body = json(e1);
        assertEquals("WASHOUT", e1Body.path("phaseState").asText());
        assertEquals(T0 + 30 * MINUTE, e1Body.path("firstEndedAt").asLong());
        assertEquals(2, e1Body.path("version").asInt());

        // 洗脱 5 分钟：10:04:59（结束前 1 秒）开始第二期拒绝 422
        clock.advance(4 * MINUTE + 59_000L);
        ResponseEntity<String> tooEarly = phase("EXP-C1", "P1", 2, "start", "req-c1-s2-early", null);
        assertEquals(422, tooEarly.getStatusCode().value());

        // 10:05:00 恰好满洗脱，可开始
        clock.advance(1_000L);
        ResponseEntity<String> s2 = phase("EXP-C1", "P1", 2, "start", "req-c1-s2", null);
        assertEquals(200, s2.getStatusCode().value());
        JsonNode s2Body = json(s2);
        assertEquals("SECOND_RUNNING", s2Body.path("phaseState").asText());
        assertEquals(T0 + 35 * MINUTE, s2Body.path("secondStartedAt").asLong());
        assertEquals(3, s2Body.path("version").asInt());

        // 结束第二阶段 → DONE
        clock.advance(20 * MINUTE);
        ResponseEntity<String> e2 = phase("EXP-C1", "P1", 2, "end", "req-c1-e2", null);
        assertEquals(200, e2.getStatusCode().value());
        JsonNode e2Body = json(e2);
        assertEquals("DONE", e2Body.path("phaseState").asText());
        assertEquals(T0 + 55 * MINUTE, e2Body.path("secondEndedAt").asLong());
        assertEquals(4, e2Body.path("version").asInt());

        // 库内最终数据核对
        assertEquals(T0, jdbc.queryForObject(
                "SELECT first_started_at FROM crossover_phase WHERE experiment_id = 'EXP-C1'",
                Long.class));
        assertEquals(T0 + 55 * MINUTE, jdbc.queryForObject(
                "SELECT second_ended_at FROM crossover_phase WHERE experiment_id = 'EXP-C1'",
                Long.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT version FROM crossover_phase WHERE experiment_id = 'EXP-C1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crossover_phase WHERE experiment_id = 'EXP-C1'",
                Integer.class));
    }

    @Test
    void endBeforeStart_andDoubleStartEnd_conflict() {
        createCrossover("EXP-C2", 5, "req-c2-create");
        register("EXP-C2", "P1", "req-c2-alloc");

        // 未开始不能结束
        assertEquals(409, phase("EXP-C2", "P1", 1, "end", "req-c2-e1-early", null)
                .getStatusCode().value());
        assertEquals(409, phase("EXP-C2", "P1", 2, "end", "req-c2-e2-early", null)
                .getStatusCode().value());
        // 第二阶段不能在第一阶段结束前开始
        assertEquals(409, phase("EXP-C2", "P1", 2, "start", "req-c2-s2-early", null)
                .getStatusCode().value());

        assertEquals(200, phase("EXP-C2", "P1", 1, "start", "req-c2-s1", null)
                .getStatusCode().value());
        // 重复开始 409
        assertEquals(409, phase("EXP-C2", "P1", 1, "start", "req-c2-s1-again", null)
                .getStatusCode().value());

        assertEquals(200, phase("EXP-C2", "P1", 1, "end", "req-c2-e1", null)
                .getStatusCode().value());
        // 重复结束 409
        assertEquals(409, phase("EXP-C2", "P1", 1, "end", "req-c2-e1-again", null)
                .getStatusCode().value());

        clock.advance(5 * MINUTE);
        assertEquals(200, phase("EXP-C2", "P1", 2, "start", "req-c2-s2", null)
                .getStatusCode().value());
        assertEquals(409, phase("EXP-C2", "P1", 2, "start", "req-c2-s2-again", null)
                .getStatusCode().value());
        assertEquals(200, phase("EXP-C2", "P1", 2, "end", "req-c2-e2", null)
                .getStatusCode().value());
        assertEquals(409, phase("EXP-C2", "P1", 2, "end", "req-c2-e2-again", null)
                .getStatusCode().value());
    }

    @Test
    void withdrawnParticipant_phaseOperationsGone() {
        createCrossover("EXP-C3", 5, "req-c3-create");
        register("EXP-C3", "P1", "req-c3-alloc1");
        register("EXP-C3", "P2", "req-c3-alloc2");

        // P1：第一阶段进行中退组，之后开始/结束均 410
        assertEquals(200, phase("EXP-C3", "P1", 1, "start", "req-c3-s1", null)
                .getStatusCode().value());
        assertEquals(200, exchange("/api/experiments/EXP-C3/participants/P1/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-c3-wd1"), null)
                .getStatusCode().value());
        assertEquals(410, phase("EXP-C3", "P1", 1, "end", "req-c3-e1", null)
                .getStatusCode().value());
        assertEquals(410, phase("EXP-C3", "P1", 2, "start", "req-c3-s2", null)
                .getStatusCode().value());

        // P2：未开始即退组，新阶段开始 410
        assertEquals(200, exchange("/api/experiments/EXP-C3/participants/P2/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-c3-wd2"), null)
                .getStatusCode().value());
        assertEquals(410, phase("EXP-C3", "P2", 1, "start", "req-c3-p2s1", null)
                .getStatusCode().value());

        // 退组不推进阶段：P1 第一阶段时间戳保持退组前状态
        assertNull(jdbc.queryForObject(
                "SELECT first_ended_at FROM crossover_phase "
                        + "WHERE experiment_id = 'EXP-C3' AND participant_id = 'P1'",
                Long.class));
    }

    @Test
    void closedExperiment_noNewStartButStartedPhaseCanEnd() throws Exception {
        createCrossover("EXP-C4", 5, "req-c4-create");
        register("EXP-C4", "P1", "req-c4-alloc1");
        register("EXP-C4", "P2", "req-c4-alloc2");

        assertEquals(200, phase("EXP-C4", "P1", 1, "start", "req-c4-s1", null)
                .getStatusCode().value());
        assertEquals(200, exchange("/api/experiments/EXP-C4/close", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-c4-close"), null).getStatusCode().value());

        // 关闭后：新开始被拒（含另一参与者的第一阶段与 P1 的第二阶段）
        assertEquals(409, phase("EXP-C4", "P2", 1, "start", "req-c4-p2s1", null)
                .getStatusCode().value());
        // 已开始阶段可结束
        assertEquals(200, phase("EXP-C4", "P1", 1, "end", "req-c4-e1", null)
                .getStatusCode().value());
        // 关闭不自动完成：P1 处于洗脱中，且第二阶段开始仍被拒
        ResponseEntity<String> view = exchange(
                "/api/experiments/EXP-C4/participants/P1/phases", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        assertEquals("WASHOUT", json(view).path("phaseState").asText());
        clock.advance(5 * MINUTE);
        assertEquals(409, phase("EXP-C4", "P1", 2, "start", "req-c4-s2", null)
                .getStatusCode().value());
    }

    @Test
    void washoutValidation_andNonCrossoverPhaseOpsRejected() throws Exception {
        // 洗脱时长边界：0 与 121 拒绝，1 与 120 允许
        assertEquals(400, exchange("/api/experiments/EXP-V0", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-v0"),
                "{\"blockCount\":2,\"washoutMinutes\":0}").getStatusCode().value());
        assertEquals(400, exchange("/api/experiments/EXP-V121", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-v121"),
                "{\"blockCount\":2,\"washoutMinutes\":121}").getStatusCode().value());
        assertEquals(201, exchange("/api/experiments/EXP-V1", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-v1"),
                "{\"blockCount\":2,\"washoutMinutes\":1}").getStatusCode().value());
        ResponseEntity<String> max = exchange("/api/experiments/EXP-V120", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-v120"),
                "{\"blockCount\":2,\"washoutMinutes\":120}");
        assertEquals(201, max.getStatusCode().value());
        assertEquals(120, json(max).path("washoutMinutes").asInt());

        // 单阶段实验不支持阶段操作与阶段查询
        assertEquals(201, exchange("/api/experiments/EXP-S1", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-s1-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        register("EXP-S1", "P1", "req-s1-alloc");
        assertEquals(409, phase("EXP-S1", "P1", 1, "start", "req-s1-s1", null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/EXP-S1/participants/P1/phases",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)
                .getStatusCode().value());
        // 非法阶段号 400；未登记参与者 404
        assertEquals(400, exchange("/api/experiments/EXP-V1/participants/P1/phases/3/start",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-v1-badphase"), null)
                .getStatusCode().value());
        assertEquals(404, phase("EXP-V1", "NOPE", 1, "start", "req-v1-nope", null)
                .getStatusCode().value());
    }
}
