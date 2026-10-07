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
 * MINIMIZE 生命周期边界：同参与者同特征按原分配重放、异特征 409；
 * 退组仍计入历史平衡；关闭后不新增；受控揭盲可获处理代码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MinimizationLifecycleTest extends AbstractBlindIntegrationTest {

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

    private void createMinimize(String expId, int capacity, String requestId) {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", requestId),
                "{\"mode\":\"MINIMIZE\",\"capacity\":" + capacity + "}").getStatusCode().value());
    }

    private ResponseEntity<String> register(String expId, String pid, String body, String requestId) {
        return post("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", requestId), body);
    }

    private String dbTreatment(String expId, String pid) {
        return jdbc.queryForObject(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = ? AND a.participant_id = ?",
                String.class, expId, pid);
    }

    @Test
    void sameParticipant_sameFeaturesReplays_originalAllocation() throws Exception {
        createMinimize("MINL-1", 4, "minl-create-1");
        ResponseEntity<String> first = register("MINL-1", "P1",
                "{\"feature1\":0,\"feature2\":0}", "minl-a1");
        assertEquals(201, first.getStatusCode().value());

        // 新 requestId、同参与者同特征：按原分配重放，不新增席位
        ResponseEntity<String> replay = register("MINL-1", "P1",
                "{\"feature1\":0,\"feature2\":0}", "minl-a1-again");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(json(first).path("blindCode").asText(), json(replay).path("blindCode").asText());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MINL-1'", Integer.class));

        // 同参与者异特征：409
        assertEquals(409, register("MINL-1", "P1", "{\"feature1\":0,\"feature2\":1}", "minl-a1-diff")
                .getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MINL-1'", Integer.class));

        // 退组后同特征仍按原分配重放（视图带 WITHDRAWN），异特征仍 409
        assertEquals(200, post("/api/experiments/MINL-1/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "minl-wd1"), null).getStatusCode().value());
        ResponseEntity<String> replayWithdrawn = register("MINL-1", "P1",
                "{\"feature1\":0,\"feature2\":0}", "minl-a1-third");
        assertEquals(201, replayWithdrawn.getStatusCode().value());
        assertEquals("WITHDRAWN", json(replayWithdrawn).path("status").asText());
        assertEquals(409, register("MINL-1", "P1", "{\"feature1\":1,\"feature2\":1}", "minl-a1-fourth")
                .getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MINL-1'", Integer.class));
    }

    @Test
    void withdrawnAllocation_stillCountsInHistoricalBalance() {
        createMinimize("MINL-2", 4, "minl-create-2");
        assertEquals(201, register("MINL-2", "P1", "{\"feature1\":0,\"feature2\":0}", "minl-b1")
                .getStatusCode().value());
        assertEquals(201, register("MINL-2", "P2", "{\"feature1\":0,\"feature2\":0}", "minl-b2")
                .getStatusCode().value());
        assertEquals("A", dbTreatment("MINL-2", "P1"));
        assertEquals("B", dbTreatment("MINL-2", "P2"));

        // 退组 B 侧参与者：历史平衡仍计入（A1 B1），第三 (0,0) 同分选 A；
        // 若退组不计入，平衡为 A1 B0，模拟 B 得 0 分会错选 B。
        assertEquals(200, post("/api/experiments/MINL-2/participants/P2/withdrawal",
                headers("c1", "COORDINATOR", "minl-wd2"), null).getStatusCode().value());
        assertEquals(201, register("MINL-2", "P3", "{\"feature1\":0,\"feature2\":0}", "minl-b3")
                .getStatusCode().value());
        assertEquals("A", dbTreatment("MINL-2", "P3"), "退组者仍计入历史平衡，同分应选 A");
    }

    @Test
    void closedExperiment_rejectsNewRegistration() {
        createMinimize("MINL-3", 4, "minl-create-3");
        assertEquals(200, post("/api/experiments/MINL-3/close",
                headers("c1", "COORDINATOR", "minl-close3"), null).getStatusCode().value());
        assertEquals(409, register("MINL-3", "P1", "{\"feature1\":0,\"feature2\":0}", "minl-c1")
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MINL-3'", Integer.class));
    }

    @Test
    void controlledUnblind_revealsMinimizeTreatment_onlyToApplicant() throws Exception {
        createMinimize("MINL-4", 4, "minl-create-4");
        assertEquals(201, register("MINL-4", "P1", "{\"feature1\":1,\"feature2\":0}", "minl-d1")
                .getStatusCode().value());
        String expected = dbTreatment("MINL-4", "P1");
        assertEquals("A", expected, "空历史同分应选 A");

        ResponseEntity<String> apply = post(
                "/api/experiments/MINL-4/participants/P1/unblind-requests",
                headers("c1", "COORDINATOR", "minl-apply"), "{\"reason\":\"合成核对处理代码\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, post("/api/unblind-requests/" + ubId + "/approval",
                headers("rev-2", "REVIEWER", "minl-approve"), null).getStatusCode().value());

        ResponseEntity<String> result = rest.exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, new HttpEntity<>(null, headers("c1", "COORDINATOR", null)),
                String.class);
        assertEquals(200, result.getStatusCode().value());
        assertEquals(expected, json(result).path("treatment").asText());

        // 普通查询仍不暴露处理代码
        JsonNode normal = json(rest.exchange("/api/experiments/MINL-4/participants/P1",
                HttpMethod.GET, new HttpEntity<>(null, headers("c1", "COORDINATOR", null)),
                String.class));
        assertFalse(normal.has("treatment"));
        assertFalse(normal.has("seatNo"));
    }
}
