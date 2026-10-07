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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINIMIZE 平衡算法：合成场景验证边际格最小化选择、退组占席与历史平衡、
 * 普通视图不暴露处理/席位/格数/分数，受控揭盲可获得处理代码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MinimizationBalanceTest extends AbstractBlindIntegrationTest {

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

    private ResponseEntity<String> register(String expId, String pid, int f1, int f2, String reqId) {
        return post("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", reqId),
                "{\"feature1\":" + f1 + ",\"feature2\":" + f2 + "}");
    }

    /** 库内核对处理代码（盲底，仅测试核对用，不经过普通接口）。 */
    private String dbTreatment(String expId, String pid) {
        return jdbc.queryForObject(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id "
                        + "AND s.block_no = a.block_no AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = ? AND a.participant_id = ?",
                String.class, expId, pid);
    }

    private int dbSeatNo(String expId, String pid) {
        return jdbc.queryForObject(
                "SELECT seat_no FROM allocation WHERE experiment_id = ? AND participant_id = ?",
                Integer.class, expId, pid);
    }

    @Test
    void syntheticScenario_firstASecondB_withdrawKeepsSeat_thirdGetsNewSeat() throws Exception {
        createMinimize("MIN-SYN", 4, "syn-create");

        // 合成连续两位同特征 (0,0)：第一分 A、第二分 B
        assertEquals(201, register("MIN-SYN", "P1", 0, 0, "syn-p1").getStatusCode().value());
        assertEquals("A", dbTreatment("MIN-SYN", "P1"));
        assertEquals(201, register("MIN-SYN", "P2", 0, 0, "syn-p2").getStatusCode().value());
        assertEquals("B", dbTreatment("MIN-SYN", "P2"));
        int p1Seat = dbSeatNo("MIN-SYN", "P1");

        // 第一退组：仍占席并计入历史平衡
        assertEquals(200, post("/api/experiments/MIN-SYN/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "syn-w1"), null).getStatusCode().value());

        // 第三同特征：不能把第一的 A 席再次占用；平衡同分选 A，但是新席位
        assertEquals(201, register("MIN-SYN", "P3", 0, 0, "syn-p3").getStatusCode().value());
        assertEquals("A", dbTreatment("MIN-SYN", "P3"));
        assertNotEquals(p1Seat, dbSeatNo("MIN-SYN", "P3"), "退组席位不得被再次占用");

        // 第四同特征：格 (0,0) 上 A=2、B=1，模拟 B 得 0 分，故分 B
        assertEquals(201, register("MIN-SYN", "P4", 0, 0, "syn-p4").getStatusCode().value());
        assertEquals("B", dbTreatment("MIN-SYN", "P4"));

        // 容量满：422
        assertEquals(422, register("MIN-SYN", "P5", 0, 0, "syn-p5").getStatusCode().value());

        // 最终数据：4 条分配（含 1 条退组），A、B 各 2，席位两两不同
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MIN-SYN'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MIN-SYN' "
                        + "AND status = 'WITHDRAWN'", Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT seat_no) FROM allocation WHERE experiment_id = 'MIN-SYN'",
                Integer.class));
    }

    @Test
    void marginalCells_balanceAcrossFeatureCombinations() {
        createMinimize("MIN-BAL", 8, "bal-create");
        // 不同特征组合进入不同边际格：P1(0,0)->A（同分），P2(1,1)->A（同分），
        // P3(0,0)->B（格(0,0)不平衡 2:0，选 B 得 1 分），P4(1,1)->B（同理）
        assertEquals(201, register("MIN-BAL", "P1", 0, 0, "bal-p1").getStatusCode().value());
        assertEquals("A", dbTreatment("MIN-BAL", "P1"));
        assertEquals(201, register("MIN-BAL", "P2", 1, 1, "bal-p2").getStatusCode().value());
        assertEquals("A", dbTreatment("MIN-BAL", "P2"));
        assertEquals(201, register("MIN-BAL", "P3", 0, 0, "bal-p3").getStatusCode().value());
        assertEquals("B", dbTreatment("MIN-BAL", "P3"));
        assertEquals(201, register("MIN-BAL", "P4", 1, 1, "bal-p4").getStatusCode().value());
        assertEquals("B", dbTreatment("MIN-BAL", "P4"));
        // 四个边际格中 (0,0) 与 (1,1) 各 1A1B，(0,1)、(1,0) 为空
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'MIN-BAL' AND a.feature1 = 0 AND a.feature2 = 0",
                Integer.class));
    }

    @Test
    void normalViews_hideTreatmentSeatScoreAndCellCounts() throws Exception {
        createMinimize("MIN-V1", 4, "v1-create");
        ResponseEntity<String> reg = register("MIN-V1", "P1", 0, 0, "v1-p1");
        assertEquals(201, reg.getStatusCode().value());
        JsonNode alloc = json(reg);
        // 普通登记响应：无处理、席位号、分数、格数；区组号恒为 1 不具解码性
        assertEquals(1, alloc.path("blockNo").asInt());
        assertFalse(alloc.has("treatment"));
        assertFalse(alloc.has("seatNo"));
        assertFalse(alloc.has("score"));
        assertFalse(alloc.has("cellCounts"));
        assertTrue(alloc.path("blindCode").asText().matches("[A-Z2-9]{12}"));

        // 普通查询视图同样不含盲底
        ResponseEntity<String> query = rest.exchange(
                "/api/experiments/MIN-V1/participants/P1", HttpMethod.GET,
                new HttpEntity<>(null, headers("r1", "REVIEWER", null)), String.class);
        assertEquals(200, query.getStatusCode().value());
        JsonNode queried = json(query);
        assertFalse(queried.has("treatment"));
        assertFalse(queried.has("seatNo"));
        assertFalse(queried.has("score"));
        assertFalse(queried.has("cellCounts"));

        // 实验视图不含处理映射与格数
        ResponseEntity<String> exp = rest.exchange("/api/experiments/MIN-V1", HttpMethod.GET,
                new HttpEntity<>(null, headers("r1", "REVIEWER", null)), String.class);
        assertEquals(200, exp.getStatusCode().value());
        JsonNode expBody = json(exp);
        assertEquals("MINIMIZE", expBody.path("mode").asText());
        assertFalse(expBody.has("treatment"));
        assertFalse(expBody.has("cellCounts"));
    }

    @Test
    void unblindFlow_onMinimizeAllocation_revealsDbTreatment() throws Exception {
        createMinimize("MIN-U1", 4, "u1-create");
        assertEquals(201, register("MIN-U1", "PA", 0, 0, "u1-pa").getStatusCode().value());
        String expected = dbTreatment("MIN-U1", "PA");

        ResponseEntity<String> apply = post(
                "/api/experiments/MIN-U1/participants/PA/unblind-requests",
                headers("coord-1", "COORDINATOR", "u1-apply"), "{\"reason\":\"核对最小化分配\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, post("/api/unblind-requests/" + ubId + "/approval",
                headers("rev-2", "REVIEWER", "u1-approve"), null).getStatusCode().value());

        ResponseEntity<String> result = rest.exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                new HttpEntity<>(null, headers("coord-1", "COORDINATOR", null)), String.class);
        assertEquals(200, result.getStatusCode().value());
        assertEquals(expected, json(result).path("treatment").asText());
    }

    @Test
    void closedExperiment_rejectsNewRegistration() {
        createMinimize("MIN-CL", 4, "cl-create");
        assertEquals(200, post("/api/experiments/MIN-CL/close",
                headers("c1", "COORDINATOR", "cl-close"), null).getStatusCode().value());
        assertEquals(409, register("MIN-CL", "P1", 0, 0, "cl-p1").getStatusCode().value());
    }
}
