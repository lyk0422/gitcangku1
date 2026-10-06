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
 * 受限置换分析主流程与输入/业务校验：
 * 合成场景 A={0,0}、B={100,100} 时 6 种分组中 2 种达到同等差异，精确 p=2/6；
 * 人数、版本、缺值、退组、未关闭等不符按 422/409 报告，失败不留统计或占键。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisApiTest extends AbstractBlindIntegrationTest {

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

    /** 建实验、登记 P1..P4（P1/P2 为 A，P3/P4 为 B）、按给定值提交版本1结果。 */
    private void setupOpenExperiment(String expId, int v1, int v2, int v3, int v4,
                                     String keyPrefix) {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", keyPrefix + "-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        int[] values = {v1, v2, v3, v4};
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, post(
                    "/api/experiments/" + expId + "/participants/P" + i + "/allocations",
                    headers("c1", "COORDINATOR", keyPrefix + "-alloc-" + i), null)
                    .getStatusCode().value());
            assertEquals(201, post(
                    "/api/experiments/" + expId + "/participants/P" + i + "/result",
                    headers("c1", "COORDINATOR", keyPrefix + "-result-" + i),
                    "{\"value\":" + values[i - 1] + "}").getStatusCode().value());
        }
    }

    private void close(String expId, String requestId) {
        assertEquals(200, post("/api/experiments/" + expId + "/close",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    private ResponseEntity<String> analyze(String expId, String role, String requestId,
                                           String body) {
        return post("/api/experiments/" + expId + "/analysis",
                headers("r1", role, requestId), body);
    }

    @Test
    void analysis_syntheticExactPermutation_pEquals2of6_responseOnlyStatistics() throws Exception {
        setupOpenExperiment("EXP-A1", 0, 0, 100, 100, "a1");

        // 未关闭：409
        assertEquals(409, analyze("EXP-A1", "REVIEWER", "a1-ana-early",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":1}")
                .getStatusCode().value());

        close("EXP-A1", "a1-close");
        ResponseEntity<String> resp = analyze("EXP-A1", "REVIEWER", "a1-ana-1",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":1}");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals("EXP-A1", body.path("experimentId").asText());
        assertEquals(1, body.path("version").asInt());
        assertEquals(4, body.path("participantCount").asInt());
        assertEquals(2, body.path("groupACount").asInt());
        assertEquals(2, body.path("groupBCount").asInt());
        assertEquals(2, body.path("k").asLong());
        assertEquals(6, body.path("n").asLong());
        assertEquals("2/6", body.path("p").asText());
        assertEquals(1_700_000_000_000L, body.path("createdAt").asLong());
        // 外部仅统计：不含个体值、参与者列表或处理映射
        assertFalse(body.has("participantIds"));
        assertFalse(body.has("treatment"));
        assertFalse(body.has("values"));
        assertFalse(body.has("resultValue"));
        assertFalse(body.has("basisJson"));

        // 成功分析冻结落库，内部依据含完整快照但不透出
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id = 'EXP-A1'", Integer.class));
        String storedIds = jdbc.queryForObject(
                "SELECT participant_ids FROM analysis WHERE experiment_id = 'EXP-A1'",
                String.class);
        assertEquals("P1,P2,P3,P4", storedIds);
        String basis = jdbc.queryForObject(
                "SELECT basis_json FROM analysis WHERE experiment_id = 'EXP-A1'", String.class);
        org.junit.jupiter.api.Assertions.assertTrue(
                basis != null && basis.contains("P1") && basis.contains("100"));

        // 普通查询不泄露结果与映射
        ResponseEntity<String> normal = rest.exchange(
                "/api/experiments/EXP-A1/participants/P1", HttpMethod.GET,
                new HttpEntity<>(null, headers("r1", "REVIEWER", null)), String.class);
        JsonNode normalBody = json(normal);
        assertFalse(normalBody.has("treatment"));
        assertFalse(normalBody.has("seatNo"));
        assertFalse(normalBody.has("resultValue"));
        assertFalse(normalBody.has("value"));
    }

    @Test
    void analysis_validationErrors_422_andFailuresLeaveNothing() {
        setupOpenExperiment("EXP-A2", 0, 0, 100, 100, "a2");
        // P5：提交后退组；P6：在组（第二区组第1席，处理A）
        for (String pid : new String[] {"P5", "P6"}) {
            assertEquals(201, post("/api/experiments/EXP-A2/participants/" + pid + "/allocations",
                    headers("c1", "COORDINATOR", "a2-alloc-" + pid), null).getStatusCode().value());
            assertEquals(201, post("/api/experiments/EXP-A2/participants/" + pid + "/result",
                    headers("c1", "COORDINATOR", "a2-result-" + pid), "{\"value\":50}")
                    .getStatusCode().value());
        }
        assertEquals(200, post("/api/experiments/EXP-A2/participants/P5/withdrawal",
                headers("c1", "COORDINATOR", "a2-withdraw"), null).getStatusCode().value());
        close("EXP-A2", "a2-close");

        // 人数不足 4 / 超过 8 / 重复 / 空元素
        assertEquals(422, analyze("EXP-A2", "REVIEWER", "a2-v-few",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\"],\"version\":1}").getStatusCode().value());
        assertEquals(422, analyze("EXP-A2", "REVIEWER", "a2-v-many",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\",\"P5\",\"P6\",\"P7\",\"P8\",\"P9\"],"
                        + "\"version\":1}").getStatusCode().value());
        assertEquals(422, analyze("EXP-A2", "REVIEWER", "a2-v-dup",
                "{\"participantIds\":[\"P1\",\"P1\",\"P2\",\"P3\"],\"version\":1}")
                .getStatusCode().value());
        // 版本不符：0 与缺失
        assertEquals(422, analyze("EXP-A2", "REVIEWER", "a2-v-v0",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":0}")
                .getStatusCode().value());
        assertEquals(422, analyze("EXP-A2", "REVIEWER", "a2-v-vnull",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"]}").getStatusCode().value());
        // 缺值：版本 2 无结果
        assertEquals(422, analyze("EXP-A2", "REVIEWER", "a2-v-missing",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":2}")
                .getStatusCode().value());
        // 未登记 / 已退组
        assertEquals(422, analyze("EXP-A2", "REVIEWER", "a2-v-unknown",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P9\"],\"version\":1}")
                .getStatusCode().value());
        assertEquals(422, analyze("EXP-A2", "REVIEWER", "a2-v-withdrawn",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P5\"],\"version\":1}")
                .getStatusCode().value());
        // 处理组人数不足：P1/P2/P6 为 A，P3 为 B，仅 1 个 B
        assertEquals(422, analyze("EXP-A2", "REVIEWER", "a2-v-group",
                "{\"participantIds\":[\"P1\",\"P2\",\"P6\",\"P3\"],\"version\":1}")
                .getStatusCode().value());
        // COORDINATOR 无分析权限：403
        assertEquals(403, analyze("EXP-A2", "COORDINATOR", "a2-v-role",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":1}")
                .getStatusCode().value());
        // 缺请求体：400
        assertEquals(400, analyze("EXP-A2", "REVIEWER", "a2-v-nobody", null)
                .getStatusCode().value());

        // 全部失败不留统计、不占幂等键
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id = 'EXP-A2'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id LIKE 'a2-v-%'",
                Integer.class));
    }
}
