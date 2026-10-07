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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINIMIZE 模式主流程：创建校验、合成最小化分配场景（第一 A、第二 B、
 * 退组后第三不占其 A 席）、特征非法 400、满额 422；普通视图不暴露处理与分数。
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

    private ResponseEntity<String> register(String expId, String pid, String body, String requestId) {
        return post("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", requestId), body);
    }

    private String dbTreatment(String expId, String pid) {
        List<String> t = jdbc.queryForList(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = ? AND a.participant_id = ?",
                String.class, expId, pid);
        return t.isEmpty() ? null : t.get(0);
    }

    private int dbSeatNo(String expId, String pid) {
        return jdbc.queryForObject(
                "SELECT seat_no FROM allocation WHERE experiment_id = ? AND participant_id = ?",
                Integer.class, expId, pid);
    }

    @Test
    void createMinimize_capacityAndModeValidation() throws Exception {
        ResponseEntity<String> resp = post("/api/experiments/MIN-1",
                headers("c1", "COORDINATOR", "min-create-1"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":4}");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals("MINIMIZE", body.path("mode").asText());
        assertEquals(1, body.path("blockCount").asInt());
        assertEquals(4, body.path("totalSeats").asInt());
        assertEquals("OPEN", body.path("status").asText());
        assertFalse(body.has("treatment"));
        assertFalse(body.has("seatNo"));
        assertFalse(body.has("score"));

        // 库内席位：单区组，前一半 A 后一半 B
        assertEquals(List.of("A", "A", "B", "B"), jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'MIN-1' ORDER BY seat_no",
                String.class));
        assertEquals(List.of(1, 1, 1, 1), jdbc.queryForList(
                "SELECT block_no FROM seat WHERE experiment_id = 'MIN-1' ORDER BY seat_no",
                Integer.class));

        // 容量非法：奇数、小于 4、大于 20、缺失；模式非法
        assertEquals(400, post("/api/experiments/MIN-B1", headers("c1", "COORDINATOR", "min-b1"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":3}").getStatusCode().value());
        assertEquals(400, post("/api/experiments/MIN-B2", headers("c1", "COORDINATOR", "min-b2"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":2}").getStatusCode().value());
        assertEquals(400, post("/api/experiments/MIN-B3", headers("c1", "COORDINATOR", "min-b3"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":22}").getStatusCode().value());
        assertEquals(400, post("/api/experiments/MIN-B4", headers("c1", "COORDINATOR", "min-b4"),
                "{\"mode\":\"MINIMIZE\"}").getStatusCode().value());
        assertEquals(400, post("/api/experiments/MIN-B5", headers("c1", "COORDINATOR", "min-b5"),
                "{\"mode\":\"RANDOM\",\"capacity\":4}").getStatusCode().value());

        // 上限 20：10 A + 10 B
        assertEquals(201, post("/api/experiments/MIN-20", headers("c1", "COORDINATOR", "min-c20"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":20}").getStatusCode().value());
        assertEquals(10L, jdbc.queryForObject("SELECT COUNT(*) FROM seat "
                + "WHERE experiment_id = 'MIN-20' AND treatment = 'A'", Long.class));
        assertEquals(10L, jdbc.queryForObject("SELECT COUNT(*) FROM seat "
                + "WHERE experiment_id = 'MIN-20' AND treatment = 'B'", Long.class));

        // 缺省仍为 BLOCK，视图带 mode 字段
        ResponseEntity<String> block = post("/api/experiments/MIN-BLK",
                headers("c1", "COORDINATOR", "min-blk"), "{\"blockCount\":2}");
        assertEquals(201, block.getStatusCode().value());
        assertEquals("BLOCK", json(block).path("mode").asText());
    }

    @Test
    void syntheticScenario_firstASecondB_withdrawnSeatNotReused() throws Exception {
        createMinimize("MIN-2", 4, "min-create-2");

        // 连续两位同特征 (0,0)：第一分 A、第二分 B
        ResponseEntity<String> r1 = register("MIN-2", "P1", "{\"feature1\":0,\"feature2\":0}", "min-a1");
        assertEquals(201, r1.getStatusCode().value());
        ResponseEntity<String> r2 = register("MIN-2", "P2", "{\"feature1\":0,\"feature2\":0}", "min-a2");
        assertEquals(201, r2.getStatusCode().value());
        assertEquals("A", dbTreatment("MIN-2", "P1"));
        assertEquals("B", dbTreatment("MIN-2", "P2"));

        // 普通盲码视图不暴露处理、席位、分数、边际格
        for (ResponseEntity<String> r : List.of(r1, r2)) {
            JsonNode node = json(r);
            assertTrue(node.path("blindCode").asText().matches("[A-Z2-9]{12}"));
            assertEquals(1, node.path("blockNo").asInt());
            assertFalse(node.has("treatment"));
            assertFalse(node.has("seatNo"));
            assertFalse(node.has("score"));
            assertFalse(node.has("cells"));
        }
        JsonNode queried = json(rest.exchange("/api/experiments/MIN-2/participants/P1",
                HttpMethod.GET, new HttpEntity<>(null, headers("r1", "REVIEWER", null)),
                String.class));
        assertFalse(queried.has("treatment"));
        assertFalse(queried.has("seatNo"));

        // 第一退组后：第三 (0,0) 仍分 A，但不能占用第一的 A 席（席 1）
        assertEquals(200, post("/api/experiments/MIN-2/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "min-wd1"), null).getStatusCode().value());
        ResponseEntity<String> r3 = register("MIN-2", "P3", "{\"feature1\":0,\"feature2\":0}", "min-a3");
        assertEquals(201, r3.getStatusCode().value());
        assertEquals("A", dbTreatment("MIN-2", "P3"));
        assertEquals(2, dbSeatNo("MIN-2", "P3"), "第三应占用新的 A 席");
        assertEquals(1, dbSeatNo("MIN-2", "P1"), "退组者的 A 席仍被占用");
        assertEquals("WITHDRAWN", jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'MIN-2' "
                        + "AND participant_id = 'P1'", String.class));

        // 第四 (0,0)：A 侧已满，分 B；第五：满额 422
        assertEquals(201, register("MIN-2", "P4", "{\"feature1\":0,\"feature2\":0}", "min-a4")
                .getStatusCode().value());
        assertEquals("B", dbTreatment("MIN-2", "P4"));
        assertEquals(422, register("MIN-2", "P5", "{\"feature1\":0,\"feature2\":0}", "min-a5")
                .getStatusCode().value());
    }

    @Test
    void register_featureValidation_andBlockModeRejectsFeatures() {
        createMinimize("MIN-3", 4, "min-create-3");
        // 缺请求体、缺特征、取值非 0/1、类型非法：均 400
        assertEquals(400, register("MIN-3", "P1", null, "min-f1").getStatusCode().value());
        assertEquals(400, register("MIN-3", "P1", "{\"feature1\":0}", "min-f2")
                .getStatusCode().value());
        assertEquals(400, register("MIN-3", "P1", "{\"feature1\":2,\"feature2\":0}", "min-f3")
                .getStatusCode().value());
        assertEquals(400, register("MIN-3", "P1", "{\"feature1\":0,\"feature2\":-1}", "min-f4")
                .getStatusCode().value());
        assertEquals(400, register("MIN-3", "P1", "{\"feature1\":\"x\",\"feature2\":0}", "min-f5")
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MIN-3'", Integer.class));

        // BLOCK 实验不接受特征参数
        assertEquals(201, post("/api/experiments/MIN-3B", headers("c1", "COORDINATOR", "min-c3b"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(400, register("MIN-3B", "P1", "{\"feature1\":0,\"feature2\":1}", "min-f6")
                .getStatusCode().value());
    }
}
