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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受限精确置换分析主流程：合成场景 A={0,0}、B={100,100}，6 种分组中 2 种达到同等差异，p=2/6；
 * 响应只含 k/n 与匿名人数；版本冻结；历史查询与权限。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExactPermutationAnalysisTest extends AbstractBlindIntegrationTest {

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

    /** 建 2 区组实验并登记前 4 人：P1、P2 为 A 席，P3、P4 为 B 席。 */
    private void setupFourParticipants(String exp, String keyPrefix) {
        assertEquals(201, post("/api/experiments/" + exp,
                headers("c1", "COORDINATOR", keyPrefix + "-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, post("/api/experiments/" + exp + "/participants/P" + i + "/allocations",
                    headers("c1", "COORDINATOR", keyPrefix + "-alloc-" + i), null)
                    .getStatusCode().value());
        }
    }

    private void submit(String exp, String pid, int value, String requestId) {
        assertEquals(201, post("/api/experiments/" + exp + "/participants/" + pid + "/results",
                headers("c1", "COORDINATOR", requestId), "{\"value\":" + value + "}")
                .getStatusCode().value());
    }

    private void close(String exp, String requestId) {
        assertEquals(200, post("/api/experiments/" + exp + "/close",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    private ResponseEntity<String> analyze(String exp, String idsJson, int version, String requestId) {
        return post("/api/experiments/" + exp + "/analyses",
                headers("r1", "REVIEWER", requestId),
                "{\"participantIds\":" + idsJson + ",\"resultVersion\":" + version + "}");
    }

    @Test
    void syntheticScenario_exactP2of6_responseContainsNoMapping() throws Exception {
        setupFourParticipants("ANA-1", "ana1");
        submit("ANA-1", "P1", 0, "ana1-sub-1");
        submit("ANA-1", "P2", 0, "ana1-sub-2");
        submit("ANA-1", "P3", 100, "ana1-sub-3");
        submit("ANA-1", "P4", 100, "ana1-sub-4");
        close("ANA-1", "ana1-close");

        ResponseEntity<String> resp = analyze("ANA-1",
                "[\"P1\",\"P2\",\"P3\",\"P4\"]", 1, "ana1-an");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals(2, body.path("favorableCount").asLong(), "k=2：两种分组达到同等差异");
        assertEquals(6, body.path("totalCount").asLong(), "n=6：C(4,2) 种分组");
        assertEquals(2, body.path("groupACount").asInt());
        assertEquals(2, body.path("groupBCount").asInt());
        assertEquals(4, body.path("participantCount").asInt());
        assertEquals(1, body.path("resultVersion").asInt());
        // 普通分析响应不得列出个体值、参与者清单或处理映射
        assertFalse(body.has("treatment"));
        assertFalse(body.has("participantIds"));
        assertFalse(body.has("values"));
        assertFalse(body.has("snapshot"));
        assertFalse(body.has("observedStatistic"));
        String analysisId = body.path("analysisId").asText();
        assertTrue(analysisId.startsWith("AN-"));

        // 库内冻结完整内部依据（含映射），仅供内部审计
        String snapshot = jdbc.queryForObject(
                "SELECT snapshot FROM analysis WHERE id = ?", String.class, analysisId);
        assertTrue(snapshot.contains("\"treatment\":\"A\""));
        assertTrue(snapshot.contains("\"resultVersion\":1"));

        // 历史查询：REVIEWER 可得同样的冻结统计
        ResponseEntity<String> got = rest.exchange("/api/analyses/" + analysisId, HttpMethod.GET,
                new HttpEntity<>(null, headers("r2", "REVIEWER", null)), String.class);
        assertEquals(200, got.getStatusCode().value());
        JsonNode gotBody = json(got);
        assertEquals(2, gotBody.path("favorableCount").asLong());
        assertEquals(6, gotBody.path("totalCount").asLong());
        assertFalse(gotBody.has("snapshot"));
        // COORDINATOR 无权查看分析：403
        assertEquals(403, rest.exchange("/api/analyses/" + analysisId, HttpMethod.GET,
                new HttpEntity<>(null, headers("c1", "COORDINATOR", null)), String.class)
                .getStatusCode().value());
    }

    @Test
    void fixedVersion_freezesValues_revisionChangesStatistic() throws Exception {
        setupFourParticipants("ANA-2", "ana2");
        submit("ANA-2", "P1", 0, "ana2-sub-1");
        submit("ANA-2", "P2", 0, "ana2-sub-2");
        submit("ANA-2", "P3", 100, "ana2-sub-3");
        submit("ANA-2", "P4", 100, "ana2-sub-4");
        // 全部四人修订到版本 2：P1 改为 100，其余值不变；版本 1 仍冻结原值
        String[] revised = {"100", "0", "100", "100"};
        for (int i = 1; i <= 4; i++) {
            assertEquals(200, post("/api/experiments/ANA-2/participants/P" + i + "/results",
                    headers("c1", "COORDINATOR", "ana2-rev-" + i), "{\"value\":" + revised[i - 1] + "}")
                    .getStatusCode().value());
        }
        close("ANA-2", "ana2-close");

        // 引用版本 1：A={0,0}、B={100,100} → k=2, n=6
        ResponseEntity<String> v1 = analyze("ANA-2", "[\"P1\",\"P2\",\"P3\",\"P4\"]", 1, "ana2-an-v1");
        assertEquals(201, v1.getStatusCode().value());
        assertEquals(2, json(v1).path("favorableCount").asLong());
        assertEquals(6, json(v1).path("totalCount").asLong());

        // 引用版本 2：值 [100,0,100,100]，任意分组的差异都与观察值相同 → k=6, n=6
        ResponseEntity<String> v2 = analyze("ANA-2", "[\"P1\",\"P2\",\"P3\",\"P4\"]", 2, "ana2-an-v2");
        assertEquals(201, v2.getStatusCode().value());
        JsonNode v2Body = json(v2);
        assertEquals(6, v2Body.path("favorableCount").asLong());
        assertEquals(6, v2Body.path("totalCount").asLong());
        assertEquals(2, v2Body.path("resultVersion").asInt());
    }
}
