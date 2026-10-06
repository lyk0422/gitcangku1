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

/**
 * 结果提交/修订：版本从 1 起每次修订加一；仅 OPEN 且未退组可写；
 * 值域 0~100；写操作幂等，失败不占键。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResultApiTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        if (actor != null) {
            h.set("X-Actor-Id", actor);
        }
        if (role != null) {
            h.set("X-Role", role);
        }
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

    private void register(String expId, String pid, String requestId) {
        assertEquals(201, post("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    private ResponseEntity<String> submit(String expId, String pid, String role,
                                          String requestId, String body) {
        return post("/api/experiments/" + expId + "/participants/" + pid + "/result",
                headers("c1", role, requestId), body);
    }

    @Test
    void submitAndRevise_versionIncrements_onlyWhileOpen() throws Exception {
        createExperiment("EXP-R1", "r1-create");
        register("EXP-R1", "P1", "r1-alloc-1");

        ResponseEntity<String> first = submit("EXP-R1", "P1", "COORDINATOR",
                "r1-result-1", "{\"value\":55}");
        assertEquals(201, first.getStatusCode().value());
        JsonNode v1 = json(first);
        assertEquals(1, v1.path("version").asInt());
        assertEquals(55, v1.path("value").asInt());
        assertEquals(1_700_000_000_000L, v1.path("updatedAt").asLong());
        // 结果视图不含盲底字段
        assertFalse(v1.has("treatment"));
        assertFalse(v1.has("seatNo"));

        clock.advance(3_000L);
        ResponseEntity<String> revised = submit("EXP-R1", "P1", "COORDINATOR",
                "r1-result-2", "{\"value\":60}");
        assertEquals(200, revised.getStatusCode().value());
        JsonNode v2 = json(revised);
        assertEquals(2, v2.path("version").asInt());
        assertEquals(60, v2.path("value").asInt());
        assertEquals(1_700_000_003_000L, v2.path("updatedAt").asLong());

        // 历史版本全部保留
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM participant_result WHERE experiment_id = 'EXP-R1'",
                Integer.class));
        assertEquals(55, jdbc.queryForObject(
                "SELECT result_value FROM participant_result "
                        + "WHERE experiment_id = 'EXP-R1' AND participant_id = 'P1' AND version = 1",
                Integer.class));
        assertEquals(60, jdbc.queryForObject(
                "SELECT result_value FROM participant_result "
                        + "WHERE experiment_id = 'EXP-R1' AND participant_id = 'P1' AND version = 2",
                Integer.class));

        // 关闭后拒绝修订
        assertEquals(200, post("/api/experiments/EXP-R1/close",
                headers("c1", "COORDINATOR", "r1-close"), null).getStatusCode().value());
        assertEquals(409, submit("EXP-R1", "P1", "COORDINATOR",
                "r1-result-3", "{\"value\":61}").getStatusCode().value());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM participant_result WHERE experiment_id = 'EXP-R1'",
                Integer.class));
    }

    @Test
    void submitResult_valueOutOfRange_400_andFailureKeepsNoKey() {
        createExperiment("EXP-R2", "r2-create");
        register("EXP-R2", "P1", "r2-alloc-1");

        assertEquals(400, submit("EXP-R2", "P1", "COORDINATOR",
                "r2-neg", "{\"value\":-1}").getStatusCode().value());
        assertEquals(400, submit("EXP-R2", "P1", "COORDINATOR",
                "r2-over", "{\"value\":101}").getStatusCode().value());
        assertEquals(400, submit("EXP-R2", "P1", "COORDINATOR",
                "r2-empty", null).getStatusCode().value());

        // 业务失败（参与者未登记 404）不占键：同一 requestId 修正参数后成功
        assertEquals(404, submit("EXP-R2", "P9", "COORDINATOR",
                "r2-retry", "{\"value\":10}").getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'r2-retry'",
                Integer.class));
        register("EXP-R2", "P9", "r2-alloc-9");
        assertEquals(201, submit("EXP-R2", "P9", "COORDINATOR",
                "r2-retry", "{\"value\":10}").getStatusCode().value());
    }

    @Test
    void submitResult_withdrawnOrWrongRole_rejected() {
        createExperiment("EXP-R3", "r3-create");
        register("EXP-R3", "P1", "r3-alloc-1");
        assertEquals(200, post("/api/experiments/EXP-R3/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "r3-withdraw"), null).getStatusCode().value());

        // 已退组：409
        assertEquals(409, submit("EXP-R3", "P1", "COORDINATOR",
                "r3-result-wd", "{\"value\":10}").getStatusCode().value());
        // REVIEWER 无权提交：403
        assertEquals(403, submit("EXP-R3", "P1", "REVIEWER",
                "r3-result-role", "{\"value\":10}").getStatusCode().value());
        // 缺身份头：401
        assertEquals(401, post("/api/experiments/EXP-R3/participants/P1/result",
                headers(null, null, "r3-result-noauth"), "{\"value\":10}").getStatusCode().value());
        // 缺 X-Request-Id：400
        assertEquals(400, submit("EXP-R3", "P1", "COORDINATOR",
                null, "{\"value\":10}").getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM participant_result WHERE experiment_id = 'EXP-R3'",
                Integer.class));
    }

    @Test
    void submitResult_idempotentReplay_sameKeySameParams() throws Exception {
        createExperiment("EXP-R4", "r4-create");
        register("EXP-R4", "P1", "r4-alloc-1");

        ResponseEntity<String> first = submit("EXP-R4", "P1", "COORDINATOR",
                "r4-key", "{\"value\":55}");
        assertEquals(201, first.getStatusCode().value());

        // 同键同参重放：原 201 与相同响应体，不追加新版本
        ResponseEntity<String> replay = submit("EXP-R4", "P1", "COORDINATOR",
                "r4-key", "{\"value\":55}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());

        // 同键异参：409
        assertEquals(409, submit("EXP-R4", "P1", "COORDINATOR",
                "r4-key", "{\"value\":56}").getStatusCode().value());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM participant_result WHERE experiment_id = 'EXP-R4'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'r4-key'",
                Integer.class));
    }
}
