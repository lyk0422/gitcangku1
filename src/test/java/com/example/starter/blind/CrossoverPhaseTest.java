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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交叉实验主流程：两阶段状态机、洗脱时长边界（10:00 结束、洗脱 5 分钟，
 * 10:04:59 拒绝、10:05:00 允许）、退组 410、关闭禁止新开始但不自动完成。
 * 所有阶段响应不得包含处理代码或席位号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossoverPhaseTest extends AbstractBlindIntegrationTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long WASHOUT_5_MIN = 5 * 60_000L;

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

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void createCrossover(String expId, int washoutMinutes, String requestId) {
        ResponseEntity<String> resp = post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":2,\"mode\":\"CROSSOVER\",\"washoutMinutes\":" + washoutMinutes + "}");
        assertEquals(201, resp.getStatusCode().value());
    }

    private void register(String expId, String pid, String requestId) {
        assertEquals(201, post("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    private ResponseEntity<String> phase(String expId, String pid, String action, String requestId) {
        return post("/api/experiments/" + expId + "/participants/" + pid + "/phases/" + action,
                headers("c1", "COORDINATOR", requestId), null);
    }

    private JsonNode getPhases(String expId, String pid) throws Exception {
        ResponseEntity<String> resp = rest.exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/phases",
                HttpMethod.GET, new HttpEntity<>(null, headers("r1", "REVIEWER", null)),
                String.class);
        assertEquals(200, resp.getStatusCode().value());
        return json(resp);
    }

    @Test
    void crossoverFullFlow_washoutBoundaryAtExactMinute() throws Exception {
        createCrossover("XO-1", 5, "xo1-create");
        register("XO-1", "P1", "xo1-alloc");

        JsonNode initial = getPhases("XO-1", "P1");
        assertEquals("NOT_STARTED", initial.path("status").asText());
        assertEquals(0, initial.path("version").asInt());
        assertEquals(5, initial.path("washoutMinutes").asInt());
        assertTrue(initial.path("firstStartedAt").isNull());

        // 未开始不能结束：409
        assertEquals(409, phase("XO-1", "P1", "first/end", "xo1-end-early")
                .getStatusCode().value());
        // 第一阶段尚未结束，不能开始第二阶段：409
        assertEquals(409, phase("XO-1", "P1", "second/start", "xo1-s2-early")
                .getStatusCode().value());

        // 10:00:00 开始第一阶段
        clock.setTime(T0);
        ResponseEntity<String> start1 = phase("XO-1", "P1", "first/start", "xo1-s1");
        assertEquals(200, start1.getStatusCode().value());
        JsonNode s1 = json(start1);
        assertEquals("FIRST_RUNNING", s1.path("status").asText());
        assertEquals(T0, s1.path("firstStartedAt").asLong());
        assertEquals(1, s1.path("version").asInt());
        assertFalse(start1.getBody().contains("treatment"), "阶段响应不得包含处理代码");
        assertFalse(start1.getBody().contains("seatNo"), "阶段响应不得包含席位号");

        // 第一阶段至多开始一次
        assertEquals(409, phase("XO-1", "P1", "first/start", "xo1-s1-again")
                .getStatusCode().value());

        // 10:00:00 结束第一阶段，洗脱 5 分钟
        long firstEnd = T0;
        ResponseEntity<String> end1 = phase("XO-1", "P1", "first/end", "xo1-e1");
        assertEquals(200, end1.getStatusCode().value());
        JsonNode e1 = json(end1);
        assertEquals("WASHOUT", e1.path("status").asText());
        assertEquals(firstEnd, e1.path("firstEndedAt").asLong());

        // 10:04:59（洗脱差 1 秒）：拒绝 422
        clock.setTime(firstEnd + WASHOUT_5_MIN - 1_000L);
        assertEquals(422, phase("XO-1", "P1", "second/start", "xo1-s2-early-2")
                .getStatusCode().value());

        // 10:05:00（洗脱满 5 分钟）：允许
        clock.setTime(firstEnd + WASHOUT_5_MIN);
        ResponseEntity<String> start2 = phase("XO-1", "P1", "second/start", "xo1-s2");
        assertEquals(200, start2.getStatusCode().value());
        JsonNode s2 = json(start2);
        assertEquals("SECOND_RUNNING", s2.path("status").asText());
        assertEquals(firstEnd + WASHOUT_5_MIN, s2.path("secondStartedAt").asLong());

        clock.advance(60_000L);
        ResponseEntity<String> end2 = phase("XO-1", "P1", "second/end", "xo1-e2");
        assertEquals(200, end2.getStatusCode().value());
        assertEquals("DONE", json(end2).path("status").asText());

        // 完成后两阶段均不可再操作
        assertEquals(409, phase("XO-1", "P1", "second/end", "xo1-e2-again")
                .getStatusCode().value());
        assertEquals(409, phase("XO-1", "P1", "second/start", "xo1-s2-again")
                .getStatusCode().value());

        // 历史：四个时刻齐全，盲码一致，无处理代码
        JsonNode history = getPhases("XO-1", "P1");
        assertEquals("DONE", history.path("status").asText());
        assertEquals(4, history.path("version").asInt());
        assertEquals(T0, history.path("firstStartedAt").asLong());
        assertEquals(firstEnd, history.path("firstEndedAt").asLong());
        assertEquals(firstEnd + WASHOUT_5_MIN, history.path("secondStartedAt").asLong());
        assertEquals(T0 + WASHOUT_5_MIN + 60_000L, history.path("secondEndedAt").asLong());
        assertEquals(s1.path("blindCode").asText(), history.path("blindCode").asText());
    }

    @Test
    void createCrossover_validationRules() {
        // 缺洗脱时长 / 越界 / 非交叉带洗脱 / 非法模式：均 400
        assertEquals(400, post("/api/experiments/XV-1", headers("c1", "COORDINATOR", "xv-1"),
                "{\"blockCount\":2,\"mode\":\"CROSSOVER\"}").getStatusCode().value());
        assertEquals(400, post("/api/experiments/XV-2", headers("c1", "COORDINATOR", "xv-2"),
                "{\"blockCount\":2,\"mode\":\"CROSSOVER\",\"washoutMinutes\":0}")
                .getStatusCode().value());
        assertEquals(400, post("/api/experiments/XV-3", headers("c1", "COORDINATOR", "xv-3"),
                "{\"blockCount\":2,\"mode\":\"CROSSOVER\",\"washoutMinutes\":121}")
                .getStatusCode().value());
        assertEquals(400, post("/api/experiments/XV-4", headers("c1", "COORDINATOR", "xv-4"),
                "{\"blockCount\":2,\"washoutMinutes\":5}").getStatusCode().value());
        assertEquals(400, post("/api/experiments/XV-5", headers("c1", "COORDINATOR", "xv-5"),
                "{\"blockCount\":2,\"mode\":\"PARALLEL\",\"washoutMinutes\":5}")
                .getStatusCode().value());

        // 默认 STANDARD：不指定 mode 成功，washoutMinutes 为 null
        ResponseEntity<String> standard = post("/api/experiments/XV-6",
                headers("c1", "COORDINATOR", "xv-6"), "{\"blockCount\":2}");
        assertEquals(201, standard.getStatusCode().value());
        assertTrue(standard.getBody().contains("\"mode\":\"STANDARD\""));
        assertTrue(standard.getBody().contains("\"washoutMinutes\":null"));

        // 非交叉实验无阶段操作：409
        register("XV-6", "P1", "xv-6-alloc");
        assertEquals(409, phase("XV-6", "P1", "first/start", "xv-6-s1")
                .getStatusCode().value());
    }

    @Test
    void withdrawnParticipant_phaseOperationsGone410_statePreserved() throws Exception {
        createCrossover("XO-2", 5, "xo2-create");
        register("XO-2", "P1", "xo2-alloc");
        assertEquals(200, phase("XO-2", "P1", "first/start", "xo2-s1").getStatusCode().value());

        assertEquals(200, post("/api/experiments/XO-2/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "xo2-withdraw"), null).getStatusCode().value());

        // 退组后一切新阶段操作 410
        assertEquals(410, phase("XO-2", "P1", "first/end", "xo2-e1").getStatusCode().value());
        assertEquals(410, phase("XO-2", "P1", "second/start", "xo2-s2").getStatusCode().value());
        assertEquals(410, phase("XO-2", "P1", "first/start", "xo2-s1-again")
                .getStatusCode().value());

        // 退组不改写阶段状态（不重排两期次序）
        JsonNode state = getPhases("XO-2", "P1");
        assertEquals("FIRST_RUNNING", state.path("status").asText());
        assertEquals(T0, state.path("firstStartedAt").asLong());
    }

    @Test
    void closedExperiment_forbidsNewStarts_allowsEndingStarted_notAutoComplete() throws Exception {
        createCrossover("XO-3", 5, "xo3-create");
        register("XO-3", "P1", "xo3-alloc-1");
        register("XO-3", "P2", "xo3-alloc-2");
        assertEquals(200, phase("XO-3", "P1", "first/start", "xo3-s1").getStatusCode().value());

        assertEquals(200, post("/api/experiments/XO-3/close",
                headers("c1", "COORDINATOR", "xo3-close"), null).getStatusCode().value());

        // 关闭禁止新开始
        assertEquals(409, phase("XO-3", "P2", "first/start", "xo3-p2-s1").getStatusCode().value());
        // 已开始阶段可结束
        assertEquals(200, phase("XO-3", "P1", "first/end", "xo3-e1").getStatusCode().value());
        // 洗脱满后仍禁止新开始（第二阶段）
        clock.advance(WASHOUT_5_MIN);
        assertEquals(409, phase("XO-3", "P1", "second/start", "xo3-s2").getStatusCode().value());
        // 关闭不自动完成：状态停留在 WASHOUT
        assertEquals("WASHOUT", getPhases("XO-3", "P1").path("status").asText());
        assertEquals("NOT_STARTED", getPhases("XO-3", "P2").path("status").asText());
    }
}
