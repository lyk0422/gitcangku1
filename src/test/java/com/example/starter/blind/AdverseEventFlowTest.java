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
 * 不良事件报告：任意角色可上报；SEVERE 自动标记 URGENT_REVIEW，其余严重度不改变分配状态；
 * 同一参与者可多条报告；报告历史与 URGENT_REVIEW 清单不泄露处理代码；幂等回放与异参 409。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdverseEventFlowTest extends AbstractBlindIntegrationTest {

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

    private void setupExperimentWithParticipant(String expId, String participantId,
                                                String requestIdPrefix) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", requestIdPrefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        register(expId, participantId, requestIdPrefix + "-alloc");
    }

    private void register(String expId, String participantId, String requestId) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + participantId + "/allocations",
                HttpMethod.POST,
                headers("coord-1", "COORDINATOR", requestId), null)
                .getStatusCode().value());
    }

    private ResponseEntity<String> report(String expId, String pid, String actor, String role,
                                          String requestId, String eventKey, String severity,
                                          String description) {
        return exchange("/api/experiments/" + expId + "/participants/" + pid + "/adverse-events",
                HttpMethod.POST, headers(actor, role, requestId),
                "{\"eventKey\":\"" + eventKey + "\",\"severity\":\"" + severity
                        + "\",\"description\":\"" + description + "\"}");
    }

    private String urgentReviewFlag(String expId, String pid) {
        return jdbc.queryForObject(
                "SELECT urgent_review FROM allocation WHERE experiment_id = ? AND participant_id = ?",
                String.class, expId, pid);
    }

    @Test
    void severeReport_marksUrgentReview_mildAndModerateDoNot() throws Exception {
        setupExperimentWithParticipant("AE-1", "PA", "ae1");
        register("AE-1", "PB", "ae1b-alloc");
        register("AE-1", "PC", "ae1c-alloc");

        // MILD：不改变分配状态
        ResponseEntity<String> mild = report("AE-1", "PA", "coord-1", "COORDINATOR",
                "ae1-mild", "EV-MILD", "MILD", "轻度头痛");
        assertEquals(201, mild.getStatusCode().value());
        JsonNode mildBody = json(mild);
        assertEquals("EV-MILD", mildBody.path("eventKey").asText());
        assertEquals("MILD", mildBody.path("severity").asText());
        assertEquals("coord-1", mildBody.path("reporterActor").asText());
        assertFalse(mildBody.has("treatment"), "报告视图不得包含处理代码");
        assertFalse(mildBody.has("seatNo"), "报告视图不得包含席位号");
        assertEquals("N", urgentReviewFlag("AE-1", "PA"));

        // MODERATE（REVIEWER 也可上报）：不改变分配状态
        assertEquals(201, report("AE-1", "PB", "rev-1", "REVIEWER",
                "ae1-mod", "EV-MOD", "MODERATE", "中度恶心").getStatusCode().value());
        assertEquals("N", urgentReviewFlag("AE-1", "PB"));

        // SEVERE：自动标记 URGENT_REVIEW
        assertEquals(201, report("AE-1", "PC", "rev-1", "REVIEWER",
                "ae1-severe", "EV-SEV", "SEVERE", "重度过敏反应").getStatusCode().value());
        assertEquals("Y", urgentReviewFlag("AE-1", "PC"));

        // URGENT_REVIEW 清单只含 PC，且不泄露处理代码与席位号
        ResponseEntity<String> urgent = exchange("/api/experiments/AE-1/urgent-reviews",
                HttpMethod.GET, headers("rev-1", "REVIEWER", null), null);
        assertEquals(200, urgent.getStatusCode().value());
        JsonNode urgentList = json(urgent);
        assertEquals(1, urgentList.size());
        assertEquals("PC", urgentList.get(0).path("participantId").asText());
        assertFalse(urgentList.get(0).has("treatment"));
        assertFalse(urgentList.get(0).has("seatNo"));

        // 报告历史：PC 一条；PA 一条；历史不泄露盲底
        ResponseEntity<String> history = exchange(
                "/api/experiments/AE-1/participants/PC/adverse-events",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, history.getStatusCode().value());
        JsonNode historyList = json(history);
        assertEquals(1, historyList.size());
        assertEquals("EV-SEV", historyList.get(0).path("eventKey").asText());
        assertFalse(historyList.get(0).has("treatment"));
    }

    @Test
    void multipleReportsPerParticipant_andDuplicateEventKeyConflict() {
        setupExperimentWithParticipant("AE-2", "PA", "ae2");

        // 同一参与者可有多条报告
        assertEquals(201, report("AE-2", "PA", "coord-1", "COORDINATOR",
                "ae2-r1", "EV-1", "MILD", "第一次").getStatusCode().value());
        assertEquals(201, report("AE-2", "PA", "rev-1", "REVIEWER",
                "ae2-r2", "EV-2", "SEVERE", "第二次").getStatusCode().value());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM adverse_event WHERE experiment_id = 'AE-2' "
                        + "AND participant_id = 'PA'", Integer.class));

        // eventKey 实验内重复：409
        assertEquals(409, report("AE-2", "PA", "coord-1", "COORDINATOR",
                "ae2-r3", "EV-1", "MILD", "重复键").getStatusCode().value());

        // 未登记参与者：404
        assertEquals(404, report("AE-2", "NOBODY", "coord-1", "COORDINATOR",
                "ae2-r4", "EV-9", "MILD", "不存在").getStatusCode().value());

        // 参数缺失：400
        assertEquals(400, exchange("/api/experiments/AE-2/participants/PA/adverse-events",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "ae2-r5"),
                "{\"eventKey\":\"EV-5\",\"severity\":\"MILD\",\"description\":\"\"}")
                .getStatusCode().value());
        assertEquals(400, exchange("/api/experiments/AE-2/participants/PA/adverse-events",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "ae2-r6"),
                "{\"eventKey\":\"EV-6\",\"description\":\"缺少严重度\"}")
                .getStatusCode().value());
    }

    @Test
    void reportIdempotency_replaySameResult_differentParamsConflict_failureReleasesKey()
            throws Exception {
        setupExperimentWithParticipant("AE-3", "PA", "ae3");

        // 同键同参重放：返回首次结果，不产生第二条报告
        ResponseEntity<String> first = report("AE-3", "PA", "coord-1", "COORDINATOR",
                "ae3-key", "EV-1", "SEVERE", "首次");
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<String> replay = report("AE-3", "PA", "coord-1", "COORDINATOR",
                "ae3-key", "EV-1", "SEVERE", "首次");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM adverse_event WHERE experiment_id = 'AE-3'",
                Integer.class));

        // 同键异参：409
        assertEquals(409, report("AE-3", "PA", "coord-1", "COORDINATOR",
                "ae3-key", "EV-2", "MILD", "异参").getStatusCode().value());

        // 失败不占键：先用该键触发 404（参与者不存在），随后同键正常上报成功
        assertEquals(404, report("AE-3", "NOBODY", "coord-1", "COORDINATOR",
                "ae3-fail-key", "EV-3", "MILD", "失败请求").getStatusCode().value());
        assertEquals(201, report("AE-3", "PA", "coord-1", "COORDINATOR",
                "ae3-fail-key", "EV-3", "MILD", "失败请求").getStatusCode().value());

        // 权限校验先于幂等回放：缺少身份头直接 401，即使 requestId 已被成功占用
        ResponseEntity<String> noAuth = rest.exchange(
                "/api/experiments/AE-3/participants/PA/adverse-events",
                HttpMethod.POST,
                new HttpEntity<>("{\"eventKey\":\"EV-1\",\"severity\":\"SEVERE\","
                        + "\"description\":\"首次\"}", noAuthHeaders("ae3-key")),
                String.class);
        assertEquals(401, noAuth.getStatusCode().value());
    }

    private HttpHeaders noAuthHeaders(String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Request-Id", requestId);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void urgentReviewList_requiresAuthentication() {
        setupExperimentWithParticipant("AE-4", "PA", "ae4");
        ResponseEntity<String> noAuth = rest.exchange("/api/experiments/AE-4/urgent-reviews",
                HttpMethod.GET, new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertEquals(401, noAuth.getStatusCode().value());
        // 无 SEVERE 报告时清单为空
        ResponseEntity<String> empty = exchange("/api/experiments/AE-4/urgent-reviews",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, empty.getStatusCode().value());
        assertEquals("[]", empty.getBody());
        // 实验不存在：404
        assertEquals(404, exchange("/api/experiments/AE-MISSING/urgent-reviews",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
    }
}
