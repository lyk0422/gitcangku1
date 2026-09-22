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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主流程：建实验、顺序分配、盲码视图、满额、退组不释放席位、关闭拒绝新增。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BlindExperimentApiTest extends AbstractBlindIntegrationTest {

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

    @Test
    void createExperiment_fixedBlocksAndSeats_twoATwoBPerBlock() {
        ResponseEntity<String> resp = exchange("/api/experiments/EXP-1", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-create-1"), "{\"blockCount\":2}");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = resp.getBody() == null ? null : parse(resp.getBody());
        assertNotNull(body);
        assertEquals("EXP-1", body.path("experimentId").asText());
        assertEquals(2, body.path("blockCount").asInt());
        assertEquals(4, body.path("seatsPerBlock").asInt());
        assertEquals(8, body.path("totalSeats").asInt());
        assertEquals("OPEN", body.path("status").asText());
        assertEquals(1_700_000_000_000L, body.path("createdAt").asLong());
        // 普通视图不得含盲底字段
        assertFalse(body.has("treatment"));
        assertFalse(body.has("seatNo"));

        // 库内席位映射：每区组按顺序两个 A、两个 B
        List<String> treatments = jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'EXP-1' "
                        + "ORDER BY block_no, seat_no", String.class);
        assertEquals(List.of("A", "A", "B", "B", "A", "A", "B", "B"), treatments);
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-1'", Integer.class));
    }

    @Test
    void register_assignsInBlockAndSeatOrder_returnsBlindViewOnly() throws Exception {
        createExperiment("EXP-2", 2, "req-create-2");

        List<JsonNode> allocations = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        int[] expectedBlocks = {1, 1, 1, 1, 2, 2, 2, 2};
        for (int i = 1; i <= 8; i++) {
            ResponseEntity<String> resp = exchange(
                    "/api/experiments/EXP-2/participants/P" + i + "/allocations",
                    HttpMethod.POST,
                    headers("c1", "COORDINATOR", "req-alloc-" + i), null);
            assertEquals(201, resp.getStatusCode().value(), "参与者 P" + i + " 应分配成功");
            JsonNode node = json(resp);
            assertEquals("P" + i, node.path("participantId").asText());
            assertEquals(expectedBlocks[i - 1], node.path("blockNo").asInt());
            assertEquals("ASSIGNED", node.path("status").asText());
            String blindCode = node.path("blindCode").asText();
            assertTrue(blindCode.matches("[A-Z2-9]{12}"), "盲码应为12位无含义随机码: " + blindCode);
            assertTrue(codes.add(blindCode), "盲码必须全局唯一");
            // 普通登记响应不得包含处理代码或席位序号
            assertFalse(node.has("treatment"), "响应不得包含处理代码");
            assertFalse(node.has("seatNo"), "响应不得包含席位序号");
            allocations.add(node);
        }

        // 第九个参与者：满额 422
        ResponseEntity<String> full = exchange(
                "/api/experiments/EXP-2/participants/P9/allocations", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-alloc-9"), null);
        assertEquals(422, full.getStatusCode().value());

        // REVIEWER 普通查询同样只见盲码视图
        ResponseEntity<String> query = exchange(
                "/api/experiments/EXP-2/participants/P1", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, query.getStatusCode().value());
        JsonNode queried = json(query);
        assertEquals(allocations.get(0).path("blindCode").asText(),
                queried.path("blindCode").asText());
        assertEquals(1, queried.path("blockNo").asInt());
        assertFalse(queried.has("treatment"));
        assertFalse(queried.has("seatNo"));
    }

    @Test
    void withdraw_keepsSeatAndShowsStatus_rejectsNewAndDuplicate() throws Exception {
        createExperiment("EXP-3", 2, "req-create-3");
        for (int i = 1; i <= 8; i++) {
            assertEquals(201, exchange(
                    "/api/experiments/EXP-3/participants/P" + i + "/allocations",
                    HttpMethod.POST, headers("c1", "COORDINATOR", "req-w-alloc-" + i), null)
                    .getStatusCode().value());
        }

        clock.advance(5_000L);
        ResponseEntity<String> withdrawn = exchange(
                "/api/experiments/EXP-3/participants/P1/withdrawal", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-withdraw-1"), null);
        assertEquals(200, withdrawn.getStatusCode().value());
        JsonNode node = json(withdrawn);
        assertEquals("WITHDRAWN", node.path("status").asText());
        assertEquals(1_700_000_005_000L, node.path("withdrawnAt").asLong());
        assertFalse(node.has("treatment"));
        assertFalse(node.has("seatNo"));

        // 普通查询返回退组状态
        ResponseEntity<String> query = exchange(
                "/api/experiments/EXP-3/participants/P1", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        assertEquals("WITHDRAWN", json(query).path("status").asText());

        // 席位不释放：新参与者仍被拒（满额）
        ResponseEntity<String> newOne = exchange(
                "/api/experiments/EXP-3/participants/P9/allocations", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-w-alloc-9"), null);
        assertEquals(422, newOne.getStatusCode().value());

        // 同参与者不占第二席（已退组也不可重新登记）
        ResponseEntity<String> duplicate = exchange(
                "/api/experiments/EXP-3/participants/P1/allocations", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-w-alloc-p1-again"), null);
        assertEquals(409, duplicate.getStatusCode().value());

        // 重复退组 409
        ResponseEntity<String> twice = exchange(
                "/api/experiments/EXP-3/participants/P1/withdrawal", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-withdraw-1-again"), null);
        assertEquals(409, twice.getStatusCode().value());

        // 席位仍保留 8 条分配
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXP-3'", Integer.class));
    }

    @Test
    void close_rejectsNewAllocations_andDoubleCloseConflicts() throws Exception {
        createExperiment("EXP-4", 2, "req-create-4");
        ResponseEntity<String> closed = exchange("/api/experiments/EXP-4/close",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-close-4"), null);
        assertEquals(200, closed.getStatusCode().value());
        assertEquals("CLOSED", json(closed).path("status").asText());

        ResponseEntity<String> allocate = exchange(
                "/api/experiments/EXP-4/participants/P1/allocations", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-closed-alloc"), null);
        assertEquals(409, allocate.getStatusCode().value());

        ResponseEntity<String> again = exchange("/api/experiments/EXP-4/close",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-close-4-again"), null);
        assertEquals(409, again.getStatusCode().value());

        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM experiment WHERE id = 'EXP-4'", String.class));

        // 查询实验信息
        ResponseEntity<String> view = exchange("/api/experiments/EXP-4", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, view.getStatusCode().value());
        assertEquals("CLOSED", json(view).path("status").asText());
    }

    @Test
    void createExperiment_maxEightBlocks_has32Seats() {
        ResponseEntity<String> resp = exchange("/api/experiments/EXP-MAX", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-create-max"), "{\"blockCount\":8}");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = parse(resp.getBody());
        assertEquals(8, body.path("blockCount").asInt());
        assertEquals(32, body.path("totalSeats").asInt());
        assertEquals(32, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-MAX'", Integer.class));
        // 每区组仍是两个 A 两个 B
        assertEquals(16L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-MAX' AND treatment = 'A'",
                Long.class));
        assertEquals(16L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-MAX' AND treatment = 'B'",
                Long.class));
    }

    @Test
    void invalidBlockCountAndMissingResources_fail() {
        ResponseEntity<String> tooFew = exchange("/api/experiments/EXP-X", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-bad-1"), "{\"blockCount\":1}");
        assertEquals(400, tooFew.getStatusCode().value());
        ResponseEntity<String> tooMany = exchange("/api/experiments/EXP-Y", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-bad-2"), "{\"blockCount\":9}");
        assertEquals(400, tooMany.getStatusCode().value());
        ResponseEntity<String> missingBody = exchange("/api/experiments/EXP-Z", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-bad-3"), null);
        assertEquals(400, missingBody.getStatusCode().value());

        assertEquals(404, exchange("/api/experiments/NOPE", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals(404, exchange(
                "/api/experiments/EXP-X/participants/P1/allocations", HttpMethod.POST,
                headers("c1", "COORDINATOR", "req-bad-4"), null).getStatusCode().value());
    }

    private void createExperiment(String id, int blockCount, String requestId) {
        ResponseEntity<String> resp = exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}");
        assertEquals(201, resp.getStatusCode().value());
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
