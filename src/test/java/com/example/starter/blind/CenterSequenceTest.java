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
 * 中心激活、剩余容量、独立盲码序列、中心登记、暂停恢复后版本切换；均落真实 H2 验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CenterSequenceTest extends AbstractBlindIntegrationTest {

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

    private JsonNode json(ResponseEntity<String> resp) throws Exception {
        assertNotNull(resp.getBody());
        return mapper.readTree(resp.getBody());
    }

    private void createExperiment(String id, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    private JsonNode activateCenter(String expId, String centerId, int cap, String reqId)
            throws Exception {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/centers/" + centerId, HttpMethod.POST,
                headers("c1", "COORDINATOR", reqId), "{\"targetCap\":" + cap + "}");
        assertEquals(201, resp.getStatusCode().value(), "中心激活应成功");
        return json(resp);
    }

    private JsonNode centerAllocate(String expId, String centerId, String pid, String reqId)
            throws Exception {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/centers/" + centerId
                        + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", reqId), null);
        assertEquals(201, resp.getStatusCode().value(), "中心登记应成功: " + pid);
        return json(resp);
    }

    @Test
    void activateCenter_provisionsSequences_andReportsRemainingCapacity() throws Exception {
        createExperiment("CS-1", "cs1-create");
        JsonNode center = activateCenter("CS-1", "CTR-A", 6, "cs1-center-a");
        assertEquals("CTR-A", center.path("centerId").asText());
        assertEquals(6, center.path("targetCap").asInt());
        assertEquals(0, center.path("allocatedCount").asLong());
        assertEquals(6, center.path("remaining").asLong());
        assertEquals("ACTIVE", center.path("status").asText());
        assertEquals(1, center.path("currentVersion").asInt());

        // 序列容量视图
        ResponseEntity<String> seqResp = exchange(
                "/api/experiments/CS-1/centers/CTR-A/sequences/1", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, seqResp.getStatusCode().value());
        JsonNode seq = json(seqResp);
        assertEquals(6, seq.path("total").asLong());
        assertEquals(0, seq.path("consumed").asLong());
        assertEquals(6, seq.path("available").asLong());
        assertEquals(1, seq.path("version").asInt());

        // 库中确有 6 条独立盲码且全部未消耗、顺序编号
        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'CS-1' "
                        + "AND center_id = 'CTR-A' AND version = 1", Integer.class));
        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'CS-1' "
                        + "AND center_id = 'CTR-A' AND version = 1 AND allocation_id IS NULL",
                Integer.class));

        // 重复激活 409；不存在中心 404
        assertEquals(409, exchange("/api/experiments/CS-1/centers/CTR-A", HttpMethod.POST,
                headers("c1", "COORDINATOR", "cs1-center-dup"), "{\"targetCap\":6}")
                .getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/CS-1/centers/NOPE", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals(404, exchange(
                "/api/experiments/NOPE/centers/CTR-A/sequences/1", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null).getStatusCode().value());
    }

    @Test
    void centerAllocation_consumesSequence_inOrder_andAttributesCenterAndVersion() throws Exception {
        createExperiment("CS-2", "cs2-create");
        activateCenter("CS-2", "CTR-A", 4, "cs2-center-a");

        for (int i = 1; i <= 4; i++) {
            JsonNode alloc = centerAllocate("CS-2", "CTR-A", "P" + i, "cs2-alloc-" + i);
            assertEquals("CTR-A", alloc.path("centerId").asText());
            assertEquals(1, alloc.path("protocolVersion").asInt());
            assertTrue(alloc.path("blindCode").asText().matches("[A-Z2-9]{12}"));
            assertFalse(alloc.has("treatment"));
            assertFalse(alloc.has("seatNo"));
        }

        // 序列按 seq_no 顺序消耗，4 条全部绑定分配
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'CS-2' "
                        + "AND center_id = 'CTR-A' AND version = 1 AND allocation_id IS NOT NULL",
                Integer.class));
        List<Integer> consumedSeqs = jdbc.queryForList(
                "SELECT seq_no FROM center_sequence WHERE experiment_id = 'CS-2' "
                        + "AND center_id = 'CTR-A' AND version = 1 AND allocation_id IS NOT NULL "
                        + "ORDER BY seq_no", Integer.class);
        assertEquals(List.of(1, 2, 3, 4), consumedSeqs, "应顺序消耗盲码序列");

        // 容量与中心视图
        JsonNode center = json(exchange("/api/experiments/CS-2/centers/CTR-A", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null));
        assertEquals(4, center.path("allocatedCount").asLong());
        assertEquals(0, center.path("remaining").asLong());

        JsonNode seq = json(exchange(
                "/api/experiments/CS-2/centers/CTR-A/sequences/1", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null));
        assertEquals(4, seq.path("consumed").asLong());
        assertEquals(0, seq.path("available").asLong());

        // 第五条：序列耗尽 422
        ResponseEntity<String> overflow = exchange(
                "/api/experiments/CS-2/centers/CTR-A/participants/P5/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "cs2-alloc-5"), null);
        assertEquals(422, overflow.getStatusCode().value());

        // 受试者归属查询
        JsonNode queried = json(exchange(
                "/api/experiments/CS-2/participants/P1", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null));
        assertEquals("CTR-A", queried.path("centerId").asText());
        assertEquals(1, queried.path("protocolVersion").asInt());
    }

    @Test
    void withdrawnAllocation_stillCountsAgainstRemainingCapacity() throws Exception {
        createExperiment("CS-3", "cs3-create");
        activateCenter("CS-3", "CTR-A", 3, "cs3-center-a");
        centerAllocate("CS-3", "CTR-A", "P1", "cs3-a1");
        centerAllocate("CS-3", "CTR-A", "P2", "cs3-a2");

        // P1 退组：席位不释放，累计已分配数仍计入剩余容量
        assertEquals(200, exchange("/api/experiments/CS-3/participants/P1/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "cs3-w1"), null)
                .getStatusCode().value());
        JsonNode center = json(exchange("/api/experiments/CS-3/centers/CTR-A", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null));
        assertEquals(2, center.path("allocatedCount").asLong(), "退组仍计入累计已分配");
        assertEquals(1, center.path("remaining").asLong());

        // 同参与者不能再次登记
        assertEquals(409, exchange(
                "/api/experiments/CS-3/centers/CTR-A/participants/P1/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "cs3-p1-again"), null)
                .getStatusCode().value());
    }

    @Test
    void suspendedCenter_rejectsAllocation_resumesOnNewVersionWithNewSequences() throws Exception {
        createExperiment("CS-4", "cs4-create");
        activateCenter("CS-4", "CTR-A", 6, "cs4-center-a");
        centerAllocate("CS-4", "CTR-A", "P1", "cs4-a1");
        centerAllocate("CS-4", "CTR-A", "P2", "cs4-a2");

        // 暂停中心
        ResponseEntity<String> suspended = exchange(
                "/api/experiments/CS-4/centers/CTR-A/suspension", HttpMethod.POST,
                headers("c1", "COORDINATOR", "cs4-suspend"), null);
        assertEquals(200, suspended.getStatusCode().value());
        assertEquals("SUSPENDED", json(suspended).path("status").asText());

        // 暂停期间拒绝登记
        assertEquals(409, exchange(
                "/api/experiments/CS-4/centers/CTR-A/participants/P3/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "cs4-a3-blocked"), null)
                .getStatusCode().value());
        // 重复暂停 409
        assertEquals(409, exchange(
                "/api/experiments/CS-4/centers/CTR-A/suspension", HttpMethod.POST,
                headers("c1", "COORDINATOR", "cs4-suspend-again"), null)
                .getStatusCode().value());

        // 暂停期间创建并生效 V2（30:70）
        assertEquals(201, exchange("/api/experiments/CS-4/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "cs4-amd"),
                "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000}")
                .getStatusCode().value());
        clock.setTime(1_700_000_010_000L);
        // 暂停中心不参与生效：无 ACTIVE 中心，V2 仍可生效（席位池/序列无中心可预留）
        assertEquals(200, exchange(
                "/api/experiments/CS-4/protocol-amendments/2/effect", HttpMethod.POST,
                headers("c1", "COORDINATOR", "cs4-effect"), null).getStatusCode().value());

        // 中心暂停期间版本不变
        JsonNode stillSuspended = json(exchange("/api/experiments/CS-4/centers/CTR-A",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertEquals(1, stillSuspended.path("currentVersion").asInt());

        // 恢复：切换到当时有效版本 V2，按剩余容量 4 预留新序列
        clock.advance(1000L);
        ResponseEntity<String> resumed = exchange(
                "/api/experiments/CS-4/centers/CTR-A/resumption", HttpMethod.POST,
                headers("c1", "COORDINATOR", "cs4-resume"), null);
        assertEquals(200, resumed.getStatusCode().value());
        JsonNode resumedBody = json(resumed);
        assertEquals("ACTIVE", resumedBody.path("status").asText());
        assertEquals(2, resumedBody.path("currentVersion").asInt());
        assertEquals(1_700_000_011_000L, resumedBody.path("resumedAt").asLong());

        // V2 序列：恰好剩余容量 4 条
        JsonNode seqV2 = json(exchange(
                "/api/experiments/CS-4/centers/CTR-A/sequences/2", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null));
        assertEquals(4, seqV2.path("total").asLong());
        assertEquals(0, seqV2.path("consumed").asLong());

        // 恢复后登记归属 V2
        JsonNode p3 = centerAllocate("CS-4", "CTR-A", "P3", "cs4-a3");
        assertEquals(2, p3.path("protocolVersion").asInt());
        assertEquals("CTR-A", p3.path("centerId").asText());

        // 未暂停直接恢复 409
        assertEquals(409, exchange(
                "/api/experiments/CS-4/centers/CTR-A/resumption", HttpMethod.POST,
                headers("c1", "COORDINATOR", "cs4-resume-again"), null)
                .getStatusCode().value());
    }
}
