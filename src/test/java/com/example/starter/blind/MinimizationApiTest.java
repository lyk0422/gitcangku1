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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MINIMIZE 模式：创建参数校验、特征校验、同参与者重放/异特征冲突、幂等边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MinimizationApiTest extends AbstractBlindIntegrationTest {

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
        ResponseEntity<String> resp = post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", requestId),
                "{\"mode\":\"MINIMIZE\",\"capacity\":" + capacity + "}");
        assertEquals(201, resp.getStatusCode().value());
    }

    @Test
    void createMinimize_validatesModeAndCapacityCombinations() throws Exception {
        // 合法：容量为偶数 4~20，区组统一 1
        ResponseEntity<String> ok = post("/api/experiments/MIN-C1",
                headers("c1", "COORDINATOR", "min-c1"), "{\"mode\":\"MINIMIZE\",\"capacity\":4}");
        assertEquals(201, ok.getStatusCode().value());
        JsonNode body = json(ok);
        assertEquals("MINIMIZE", body.path("mode").asText());
        assertEquals(1, body.path("blockCount").asInt());
        assertEquals(4, body.path("totalSeats").asInt());
        assertEquals(4, body.path("seatsPerBlock").asInt());

        assertEquals(201, post("/api/experiments/MIN-C20",
                headers("c1", "COORDINATOR", "min-c20"), "{\"mode\":\"MINIMIZE\",\"capacity\":20}")
                .getStatusCode().value());

        // 非法容量：奇数、越界、缺失
        assertEquals(400, post("/api/experiments/MIN-B1",
                headers("c1", "COORDINATOR", "min-b1"), "{\"mode\":\"MINIMIZE\",\"capacity\":5}")
                .getStatusCode().value());
        assertEquals(400, post("/api/experiments/MIN-B2",
                headers("c1", "COORDINATOR", "min-b2"), "{\"mode\":\"MINIMIZE\",\"capacity\":2}")
                .getStatusCode().value());
        assertEquals(400, post("/api/experiments/MIN-B3",
                headers("c1", "COORDINATOR", "min-b3"), "{\"mode\":\"MINIMIZE\",\"capacity\":22}")
                .getStatusCode().value());
        assertEquals(400, post("/api/experiments/MIN-B4",
                headers("c1", "COORDINATOR", "min-b4"), "{\"mode\":\"MINIMIZE\"}")
                .getStatusCode().value());
        // MINIMIZE 不得携带 blockCount；BLOCK 不得携带 capacity；未知 mode
        assertEquals(400, post("/api/experiments/MIN-B5",
                headers("c1", "COORDINATOR", "min-b5"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":4,\"blockCount\":2}").getStatusCode().value());
        assertEquals(400, post("/api/experiments/MIN-B6",
                headers("c1", "COORDINATOR", "min-b6"),
                "{\"blockCount\":2,\"capacity\":8}").getStatusCode().value());
        assertEquals(400, post("/api/experiments/MIN-B7",
                headers("c1", "COORDINATOR", "min-b7"),
                "{\"mode\":\"RANDOM\",\"capacity\":4}").getStatusCode().value());
        // 缺省 mode 仍为 BLOCK
        ResponseEntity<String> block = post("/api/experiments/MIN-B8",
                headers("c1", "COORDINATOR", "min-b8"), "{\"blockCount\":2}");
        assertEquals(201, block.getStatusCode().value());
        assertEquals("BLOCK", json(block).path("mode").asText());
        // 非法请求均不占幂等键
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'min-b1'",
                Integer.class));
    }

    @Test
    void createMinimize_seatsAreHalfAHalfBInSingleBlock() {
        createMinimize("MIN-S1", 6, "min-s1");
        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'MIN-S1'", Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'MIN-S1' AND treatment = 'A'",
                Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'MIN-S1' AND treatment = 'B'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'MIN-S1' AND block_no <> 1",
                Integer.class));
    }

    @Test
    void register_requiresValidBinaryFeatures() {
        createMinimize("MIN-F1", 4, "min-f1");
        String path = "/api/experiments/MIN-F1/participants/P1/allocations";
        // 缺请求体 / 缺特征 / 非 0/1 均 400
        assertEquals(400, post(path, headers("c1", "COORDINATOR", "min-f1-a"), null)
                .getStatusCode().value());
        assertEquals(400, post(path, headers("c1", "COORDINATOR", "min-f1-b"),
                "{\"feature1\":0}").getStatusCode().value());
        assertEquals(400, post(path, headers("c1", "COORDINATOR", "min-f1-c"),
                "{\"feature1\":2,\"feature2\":0}").getStatusCode().value());
        assertEquals(400, post(path, headers("c1", "COORDINATOR", "min-f1-d"),
                "{\"feature1\":0,\"feature2\":-1}").getStatusCode().value());
        // 失败不占键：同一 requestId 换合法特征可成功
        assertEquals(201, post(path, headers("c1", "COORDINATOR", "min-f1-c"),
                "{\"feature1\":0,\"feature2\":0}").getStatusCode().value());
    }

    @Test
    void register_blockModeRejectsFeatures() {
        assertEquals(201, post("/api/experiments/MIN-F2",
                headers("c1", "COORDINATOR", "min-f2-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(400, post("/api/experiments/MIN-F2/participants/P1/allocations",
                headers("c1", "COORDINATOR", "min-f2-a"),
                "{\"feature1\":0,\"feature2\":1}").getStatusCode().value());
        // 不携带特征仍按原固定区组流程登记
        assertEquals(201, post("/api/experiments/MIN-F2/participants/P1/allocations",
                headers("c1", "COORDINATOR", "min-f2-b"), null).getStatusCode().value());
    }

    @Test
    void register_sameParticipantReplaysOriginal_differentFeaturesConflict() throws Exception {
        createMinimize("MIN-R1", 4, "min-r1");
        String path = "/api/experiments/MIN-R1/participants/P1/allocations";
        ResponseEntity<String> first = post(path, headers("c1", "COORDINATOR", "min-r1-a"),
                "{\"feature1\":0,\"feature2\":1}");
        assertEquals(201, first.getStatusCode().value());
        String blindCode = json(first).path("blindCode").asText();

        // 同参与者同特征（新 requestId）：按原分配重放，盲码一致，不产生新行
        ResponseEntity<String> replay = post(path, headers("c1", "COORDINATOR", "min-r1-b"),
                "{\"feature1\":0,\"feature2\":1}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(blindCode, json(replay).path("blindCode").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MIN-R1'", Integer.class));

        // 同参与者异特征：409
        assertEquals(409, post(path, headers("c1", "COORDINATOR", "min-r1-c"),
                "{\"feature1\":1,\"feature2\":1}").getStatusCode().value());

        // 退组后同特征仍按原分配重放（状态 WITHDRAWN），不重新占席
        assertEquals(200, post("/api/experiments/MIN-R1/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "min-r1-w"), null).getStatusCode().value());
        ResponseEntity<String> replayWithdrawn = post(path,
                headers("c1", "COORDINATOR", "min-r1-d"), "{\"feature1\":0,\"feature2\":1}");
        assertEquals(201, replayWithdrawn.getStatusCode().value());
        JsonNode withdrawnBody = json(replayWithdrawn);
        assertEquals(blindCode, withdrawnBody.path("blindCode").asText());
        assertEquals("WITHDRAWN", withdrawnBody.path("status").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MIN-R1'", Integer.class));
    }

    @Test
    void register_idempotencyCoversFeatures() throws Exception {
        createMinimize("MIN-I1", 4, "min-i1");
        String path = "/api/experiments/MIN-I1/participants/P1/allocations";
        ResponseEntity<String> first = post(path, headers("c1", "COORDINATOR", "min-i1-k"),
                "{\"feature1\":1,\"feature2\":0}");
        assertEquals(201, first.getStatusCode().value());
        // 同键同参：原样回放
        ResponseEntity<String> replay = post(path, headers("c1", "COORDINATOR", "min-i1-k"),
                "{\"feature1\":1,\"feature2\":0}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        // 同键异参（换特征）：409
        assertEquals(409, post(path, headers("c1", "COORDINATOR", "min-i1-k"),
                "{\"feature1\":0,\"feature2\":0}").getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MIN-I1'", Integer.class));
    }
}
