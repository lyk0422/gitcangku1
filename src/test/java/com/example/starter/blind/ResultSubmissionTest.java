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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 结果提交/修订：版本从 1 递增、历史保留；仅 OPEN 且未退组可写；
 * 幂等重放、异参 409、失败不占键。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResultSubmissionTest extends AbstractBlindIntegrationTest {

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
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String id, String requestId) {
        assertEquals(201, post("/api/experiments/" + id,
                headers("c1", "COORDINATOR", requestId), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    private void register(String exp, String pid, String requestId) {
        assertEquals(201, post("/api/experiments/" + exp + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    private ResponseEntity<String> submit(String exp, String pid, int value, String requestId) {
        return post("/api/experiments/" + exp + "/participants/" + pid + "/results",
                headers("c1", "COORDINATOR", requestId), "{\"value\":" + value + "}");
    }

    @Test
    void submitThenRevise_versionsIncreaseAndHistoryKept() throws Exception {
        createExperiment("RES-1", "res1-create");
        register("RES-1", "P1", "res1-alloc");

        ResponseEntity<String> first = submit("RES-1", "P1", 10, "res1-sub-1");
        assertEquals(201, first.getStatusCode().value());
        JsonNode firstBody = json(first);
        assertEquals(10, firstBody.path("value").asInt());
        assertEquals(1, firstBody.path("version").asInt());
        assertEquals(1_700_000_000_000L, firstBody.path("submittedAt").asLong());
        // 结果视图不得泄露处理信息
        assertFalse(firstBody.has("treatment"));
        assertFalse(firstBody.has("seatNo"));

        clock.advance(1_000L);
        ResponseEntity<String> revised = submit("RES-1", "P1", 20, "res1-sub-2");
        assertEquals(200, revised.getStatusCode().value());
        JsonNode revisedBody = json(revised);
        assertEquals(20, revisedBody.path("value").asInt());
        assertEquals(2, revisedBody.path("version").asInt());
        assertEquals(1_700_000_001_000L, revisedBody.path("submittedAt").asLong());

        // 历史版本全部保留
        List<Integer> values = jdbc.queryForList(
                "SELECT result_value FROM result_version "
                        + "WHERE experiment_id = 'RES-1' AND participant_id = 'P1' ORDER BY version",
                Integer.class);
        assertEquals(List.of(10, 20), values);
    }

    @Test
    void submit_rejectsClosedWithdrawnUnknownAndOutOfRange() {
        createExperiment("RES-2", "res2-create");
        register("RES-2", "P1", "res2-alloc-1");

        // 值越界：400（请求校验，先于业务与幂等）
        assertEquals(400, submit("RES-2", "P1", 101, "res2-sub-high").getStatusCode().value());
        assertEquals(400, post("/api/experiments/RES-2/participants/P1/results",
                headers("c1", "COORDINATOR", "res2-sub-neg"), "{\"value\":-1}")
                .getStatusCode().value());
        // 未登记参与者：404
        assertEquals(404, submit("RES-2", "P9", 50, "res2-sub-unknown").getStatusCode().value());
        // 退组后：409
        assertEquals(200, post("/api/experiments/RES-2/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "res2-withdraw"), null).getStatusCode().value());
        assertEquals(409, submit("RES-2", "P1", 50, "res2-sub-withdrawn").getStatusCode().value());
        // 关闭后：409
        register("RES-2", "P2", "res2-alloc-2");
        assertEquals(200, post("/api/experiments/RES-2/close",
                headers("c1", "COORDINATOR", "res2-close"), null).getStatusCode().value());
        assertEquals(409, submit("RES-2", "P2", 50, "res2-sub-closed").getStatusCode().value());
        // REVIEWER 无权提交：403
        assertEquals(403, post("/api/experiments/RES-2/participants/P2/results",
                headers("r1", "REVIEWER", "res2-sub-reviewer"), "{\"value\":50}")
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM result_version WHERE experiment_id = 'RES-2'", Integer.class));
    }

    @Test
    void submit_idempotency_replayConflictAndFailedKeyReusable() throws Exception {
        createExperiment("RES-3", "res3-create");
        register("RES-3", "P1", "res3-alloc");

        ResponseEntity<String> first = submit("RES-3", "P1", 30, "res3-key-1");
        assertEquals(201, first.getStatusCode().value());
        // 同键同参重放：原 201 与响应体，不产生新版本
        ResponseEntity<String> replay = submit("RES-3", "P1", 30, "res3-key-1");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM result_version "
                        + "WHERE experiment_id = 'RES-3' AND participant_id = 'P1'", Integer.class));
        // 同键异参：409
        assertEquals(409, submit("RES-3", "P1", 40, "res3-key-1").getStatusCode().value());

        // 失败不占键：未登记参与者 404 后，同键换合法参数成功
        assertEquals(404, submit("RES-3", "P2", 50, "res3-key-2").getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'res3-key-2'",
                Integer.class));
        register("RES-3", "P2", "res3-alloc-2");
        assertEquals(201, submit("RES-3", "P2", 50, "res3-key-2").getStatusCode().value());
    }
}
