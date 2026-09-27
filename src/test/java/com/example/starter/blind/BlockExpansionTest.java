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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 区组扩容：追加区组不重排已分配参与者；退役代码不产生新席位；
 * 上限、关闭、幂等与参数校验边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BlockExpansionTest extends AbstractBlindIntegrationTest {

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
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String id, int blockCount, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}").getStatusCode().value());
    }

    private ResponseEntity<String> expand(String expId, int additional, String requestId) {
        return exchange("/api/experiments/" + expId + "/blocks", HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"additionalBlocks\":" + additional + "}");
    }

    @Test
    void expand_appendsBlocksWithoutReordering_andNewBlocksAcceptAllocations() throws Exception {
        createExperiment("EXP-E1", 2, "e1-create");
        assertEquals(201, exchange("/api/experiments/EXP-E1/participants/P1/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "e1-a1"), null)
                .getStatusCode().value());

        ResponseEntity<String> expanded = expand("EXP-E1", 2, "e1-expand");
        assertEquals(201, expanded.getStatusCode().value());
        JsonNode body = json(expanded);
        assertEquals(2, body.path("addedBlocks").asInt());
        assertEquals(4, body.path("blockCount").asInt());
        assertEquals(8, body.path("addedSeats").asInt());
        assertEquals("OPEN", body.path("status").asText());

        // 库内：16 席，新区组 3、4 仍是两个 A 两个 B；既有分配未重排
        assertEquals(16, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-E1'", Integer.class));
        List<String> block3 = jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'EXP-E1' AND block_no = 3 "
                        + "ORDER BY seat_no", String.class);
        assertEquals(List.of("A", "A", "B", "B"), block3);
        assertEquals(1, jdbc.queryForObject(
                "SELECT block_no FROM allocation WHERE experiment_id = 'EXP-E1' "
                        + "AND participant_id = 'P1'", Integer.class));

        // 扩容后的区组参与顺序分配：填满 16 席后第 17 人 422
        for (int i = 2; i <= 16; i++) {
            assertEquals(201, exchange("/api/experiments/EXP-E1/participants/P" + i + "/allocations",
                    HttpMethod.POST, headers("c1", "COORDINATOR", "e1-a" + i), null)
                    .getStatusCode().value(), "P" + i + " 应分配成功");
        }
        assertEquals(4, json(exchange("/api/experiments/EXP-E1/participants/P16",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null))
                .path("blockNo").asInt());
        assertEquals(422, exchange("/api/experiments/EXP-E1/participants/P17/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "e1-a17"), null)
                .getStatusCode().value());
    }

    @Test
    void expandAfterRetirement_newBlocksContainOnlyActiveCodeSeats() throws Exception {
        createExperiment("EXP-E2", 2, "e2-create");
        // 退役 A：4 个 A 席全部封存
        assertEquals(201, exchange("/api/experiments/EXP-E2/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "e2-retire"),
                "{\"codes\":[\"A\"],\"reason\":\"供应批次召回\"}").getStatusCode().value());

        ResponseEntity<String> expanded = expand("EXP-E2", 1, "e2-expand");
        assertEquals(201, expanded.getStatusCode().value());
        JsonNode body = json(expanded);
        assertEquals(3, body.path("blockCount").asInt());
        assertEquals(2, body.path("addedSeats").asInt(), "退役代码不产生新席位");

        // 新区组 3 只有 B 席（席位号 3、4），没有新建 A 席
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-E2' AND block_no = 3 "
                        + "AND treatment = 'A'", Integer.class));
        List<String> block3 = jdbc.queryForList(
                "SELECT CONCAT(seat_no, ':', treatment, ':', status) FROM seat "
                        + "WHERE experiment_id = 'EXP-E2' AND block_no = 3 ORDER BY seat_no",
                String.class);
        assertEquals(List.of("3:B:ACTIVE", "4:B:ACTIVE"), block3);

        // 容量诊断：区组3 总席位 2、可用 2；A 配额不因扩容增长
        JsonNode capacity = json(exchange("/api/experiments/EXP-E2/capacity",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        JsonNode block3View = capacity.path("blocks").get(2);
        assertEquals(3, block3View.path("blockNo").asInt());
        assertEquals(2, block3View.path("totalSeats").asInt());
        assertEquals(2, block3View.path("availableSeats").asInt());
        JsonNode capA = capacity.path("codes").get(0);
        assertEquals("RETIRED", capA.path("status").asText());
        assertEquals(4, capA.path("quotaTotal").asInt());
        JsonNode capB = capacity.path("codes").get(1);
        assertEquals(6, capB.path("quotaTotal").asInt());
        assertEquals(6, capB.path("remainingAllocatable").asInt());

        // 新分配只落在 B 席，最终到达区组 3
        for (int i = 1; i <= 6; i++) {
            assertEquals(201, exchange("/api/experiments/EXP-E2/participants/P" + i + "/allocations",
                    HttpMethod.POST, headers("c1", "COORDINATOR", "e2-a" + i), null)
                    .getStatusCode().value());
        }
        assertEquals(3, json(exchange("/api/experiments/EXP-E2/participants/P6",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null))
                .path("blockNo").asInt());
        assertEquals(422, exchange("/api/experiments/EXP-E2/participants/P7/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "e2-a7"), null)
                .getStatusCode().value());
    }

    @Test
    void expand_limitsClosedAndValidation_areEnforced() throws Exception {
        // 上限：8 区组实验再扩即 422，含实际值/要求值/差额
        createExperiment("EXP-E3", 8, "e3-create");
        ResponseEntity<String> tooMany = expand("EXP-E3", 1, "e3-expand");
        assertEquals(422, tooMany.getStatusCode().value());
        JsonNode violation = json(tooMany).path("violations").get(0);
        assertEquals("EXPANSION_EXCEEDS_MAX", violation.path("reasonCode").asText());
        assertEquals(9, violation.path("actual").asLong());
        assertEquals(8, violation.path("required").asLong());
        assertEquals(1, violation.path("difference").asLong());

        // 2 区组扩 6 个到上限 8：成功；再扩 1 个：422
        createExperiment("EXP-E4", 2, "e4-create");
        assertEquals(201, expand("EXP-E4", 6, "e4-expand-6").getStatusCode().value());
        assertEquals(8, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXP-E4'", Integer.class));
        assertEquals(422, expand("EXP-E4", 1, "e4-expand-more").getStatusCode().value());

        // 参数校验：0、负数、超过单次上限 6、缺请求体
        createExperiment("EXP-E5", 2, "e5-create");
        assertEquals(400, expand("EXP-E5", 0, "e5-bad-0").getStatusCode().value());
        assertEquals(400, expand("EXP-E5", -1, "e5-bad-neg").getStatusCode().value());
        assertEquals(400, expand("EXP-E5", 7, "e5-bad-7").getStatusCode().value());
        assertEquals(400, exchange("/api/experiments/EXP-E5/blocks", HttpMethod.POST,
                headers("c1", "COORDINATOR", "e5-bad-body"), null).getStatusCode().value());

        // 已关闭实验拒绝扩容：409 EXPERIMENT_CLOSED
        assertEquals(200, exchange("/api/experiments/EXP-E5/close", HttpMethod.POST,
                headers("c1", "COORDINATOR", "e5-close"), null).getStatusCode().value());
        ResponseEntity<String> closed = expand("EXP-E5", 1, "e5-expand-closed");
        assertEquals(409, closed.getStatusCode().value());
        assertEquals("EXPERIMENT_CLOSED",
                json(closed).path("violations").get(0).path("reasonCode").asText());

        // 实验不存在 404；REVIEWER 无权扩容 403
        assertEquals(404, expand("NOPE", 1, "e5-expand-nope").getStatusCode().value());
        assertEquals(403, exchange("/api/experiments/EXP-E4/blocks", HttpMethod.POST,
                headers("r1", "REVIEWER", "e5-expand-reviewer"),
                "{\"additionalBlocks\":1}").getStatusCode().value());
    }

    @Test
    void expandWrites_areIdempotent_replayDoesNotDuplicateBlocks() throws Exception {
        createExperiment("EXP-E6", 2, "e6-create");
        ResponseEntity<String> first = expand("EXP-E6", 1, "e6-expand-key");
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<String> replay = expand("EXP-E6", 1, "e6-expand-key");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        // 重放不重复建区组
        assertEquals(3, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXP-E6'", Integer.class));
        assertEquals(12, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-E6'", Integer.class));
        // 同键异参：409
        assertEquals(409, expand("EXP-E6", 2, "e6-expand-key").getStatusCode().value());
    }
}
