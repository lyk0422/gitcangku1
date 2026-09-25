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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不良事件报告：严重度分级、任意角色可报告、报告不泄露处理代码、
 * 同一参与者多条报告、eventKey 去重、历史与 URGENT_REVIEW 清单查询。
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
        assertTrue(response.getBody() != null && !response.getBody().isBlank());
        return mapper.readTree(response.getBody());
    }

    private void setupParticipant(String expId, String pid, String prefix) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", prefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST,
                headers("coord-1", "COORDINATOR", prefix + "-alloc"), null)
                .getStatusCode().value());
    }

    @Test
    void severeReport_marksUrgentReview_mildAndModerateDoNot() throws Exception {
        setupParticipant("AE-1", "P1", "ae1");

        // MILD：两种角色都可报告；不改变分配状态
        ResponseEntity<String> mild = exchange(
                "/api/experiments/AE-1/participants/P1/adverse-events", HttpMethod.POST,
                headers("rev-9", "REVIEWER", "ae1-mild"),
                "{\"eventKey\":\"EV-MILD\",\"severity\":\"MILD\",\"description\":\"轻度头痛\"}");
        assertEquals(201, mild.getStatusCode().value());
        JsonNode mildBody = json(mild);
        assertEquals("EV-MILD", mildBody.path("eventKey").asText());
        assertEquals("MILD", mildBody.path("severity").asText());
        assertEquals("rev-9", mildBody.path("reporterActor").asText());
        assertEquals("REVIEWER", mildBody.path("reporterRole").asText());
        assertFalse(mildBody.has("treatment"), "报告视图不得含处理代码");
        assertFalse(mildBody.has("seatNo"), "报告视图不得含席位号");
        assertTrue(mildBody.path("id").asLong() > 0);
        assertTrue(mildBody.path("unblindRequestId").isNull());

        // MODERATE：协调员也可报告；仍不标记
        assertEquals(201, exchange(
                "/api/experiments/AE-1/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ae1-moderate"),
                "{\"eventKey\":\"EV-MOD\",\"severity\":\"MODERATE\",\"description\":\"持续呕吐\"}")
                .getStatusCode().value());

        // 清单为空
        ResponseEntity<String> urgentEmpty = exchange(
                "/api/experiments/AE-1/urgent-review-allocations", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, urgentEmpty.getStatusCode().value());
        assertEquals("[]", urgentEmpty.getBody());

        // SEVERE：自动标记 URGENT_REVIEW
        clock.advance(5_000L);
        ResponseEntity<String> severe = exchange(
                "/api/experiments/AE-1/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-2", "COORDINATOR", "ae1-severe"),
                "{\"eventKey\":\"EV-SEV\",\"severity\":\"SEVERE\",\"description\":\"过敏性休克\"}");
        assertEquals(201, severe.getStatusCode().value());
        assertEquals(1_700_000_005_000L, json(severe).path("createdAt").asLong());

        // 清单出现该分配，且不含盲底
        ResponseEntity<String> urgent = exchange(
                "/api/experiments/AE-1/urgent-review-allocations", HttpMethod.GET,
                headers("rev-9", "REVIEWER", null), null);
        assertEquals(200, urgent.getStatusCode().value());
        JsonNode urgentBody = json(urgent);
        assertEquals(1, urgentBody.size());
        assertEquals("P1", urgentBody.get(0).path("participantId").asText());
        assertFalse(urgentBody.get(0).has("treatment"));
        assertFalse(urgentBody.get(0).has("seatNo"));

        // 数据库标记落库
        assertEquals(1, jdbc.queryForObject(
                "SELECT urgent_review FROM allocation WHERE experiment_id = 'AE-1' "
                        + "AND participant_id = 'P1'", Integer.class));

        // 第二条 SEVERE 报告：标记幂等，清单仍只有一条
        assertEquals(201, exchange(
                "/api/experiments/AE-1/participants/P1/adverse-events", HttpMethod.POST,
                headers("rev-9", "REVIEWER", "ae1-severe-2"),
                "{\"eventKey\":\"EV-SEV-2\",\"severity\":\"SEVERE\",\"description\":\"再次严重\"}")
                .getStatusCode().value());
        List<Integer> count = jdbc.queryForList(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'AE-1' "
                        + "AND urgent_review = 1", Integer.class);
        assertEquals(1, count.get(0));
    }

    @Test
    void reportHistory_multipleReports_orderedByTime_andDoesNotLeakTreatment() throws Exception {
        setupParticipant("AE-2", "P1", "ae2");

        for (String[] spec : new String[][]{
                {"EV-1", "MILD", "ae2-r1"},
                {"EV-2", "SEVERE", "ae2-r2"},
                {"EV-3", "MODERATE", "ae2-r3"}}) {
            clock.advance(1_000L);
            assertEquals(201, exchange(
                    "/api/experiments/AE-2/participants/P1/adverse-events", HttpMethod.POST,
                    headers("coord-1", "COORDINATOR", spec[2]),
                    "{\"eventKey\":\"" + spec[0] + "\",\"severity\":\"" + spec[1]
                            + "\",\"description\":\"合成事件 " + spec[0] + "\"}")
                    .getStatusCode().value());
        }

        ResponseEntity<String> history = exchange(
                "/api/experiments/AE-2/participants/P1/adverse-events", HttpMethod.GET,
                headers("rev-7", "REVIEWER", null), null);
        assertEquals(200, history.getStatusCode().value());
        JsonNode body = json(history);
        assertEquals(3, body.size());
        assertEquals("EV-1", body.get(0).path("eventKey").asText());
        assertEquals("EV-2", body.get(1).path("eventKey").asText());
        assertEquals("EV-3", body.get(2).path("eventKey").asText());
        for (JsonNode report : body) {
            assertFalse(report.has("treatment"));
            assertFalse(report.has("seatNo"));
        }

        // 报告表本身不含任何处理代码列
        assertEquals(0, jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = 'adverse_event_report' "
                        + "AND (UPPER(COLUMN_NAME) LIKE '%TREATMENT%' "
                        + "OR UPPER(COLUMN_NAME) LIKE '%SEAT%')")
                .size());
    }

    @Test
    void reportFailures_duplicateEventKey_badSeverity_unknownParticipant() {
        setupParticipant("AE-3", "P1", "ae3");

        assertEquals(201, exchange(
                "/api/experiments/AE-3/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ae3-first"),
                "{\"eventKey\":\"EV-DUP\",\"severity\":\"MILD\",\"description\":\"首次\"}")
                .getStatusCode().value());

        // 同一分配重复 eventKey：409
        assertEquals(409, exchange(
                "/api/experiments/AE-3/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ae3-dup"),
                "{\"eventKey\":\"EV-DUP\",\"severity\":\"SEVERE\",\"description\":\"重复键\"}")
                .getStatusCode().value());

        // 非法 severity：400
        assertEquals(400, exchange(
                "/api/experiments/AE-3/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ae3-bad-sev"),
                "{\"eventKey\":\"EV-BAD\",\"severity\":\"CRITICAL\",\"description\":\"x\"}")
                .getStatusCode().value());

        // severity 缺失：400（Bean Validation）
        assertEquals(400, exchange(
                "/api/experiments/AE-3/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ae3-no-sev"),
                "{\"eventKey\":\"EV-NOSEV\",\"description\":\"x\"}")
                .getStatusCode().value());

        // 未登记参与者：404
        assertEquals(404, exchange(
                "/api/experiments/AE-3/participants/NOBODY/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ae3-nobody"),
                "{\"eventKey\":\"EV-X\",\"severity\":\"MILD\",\"description\":\"x\"}")
                .getStatusCode().value());

        // 不存在的实验：404
        assertEquals(404, exchange(
                "/api/experiments/NOPE/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ae3-noexp"),
                "{\"eventKey\":\"EV-X\",\"severity\":\"MILD\",\"description\":\"x\"}")
                .getStatusCode().value());

        // 缺身份头：401
        assertEquals(401, exchange(
                "/api/experiments/AE-3/participants/P1/adverse-events", HttpMethod.POST,
                headers(null, null, "ae3-noauth"),
                "{\"eventKey\":\"EV-Y\",\"severity\":\"MILD\",\"description\":\"x\"}")
                .getStatusCode().value());

        // 最终只有 1 条报告
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM adverse_event_report WHERE allocation_id = "
                        + "(SELECT id FROM allocation WHERE experiment_id = 'AE-3' "
                        + "AND participant_id = 'P1')", Integer.class));
    }

    @Test
    void reports_areIdempotent_sameKeyReplaysOriginal() throws Exception {
        setupParticipant("AE-4", "P1", "ae4");

        String body = "{\"eventKey\":\"EV-IDEM\",\"severity\":\"SEVERE\",\"description\":\"幂等事件\"}";
        ResponseEntity<String> first = exchange(
                "/api/experiments/AE-4/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ae4-key"), body);
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<String> replay = exchange(
                "/api/experiments/AE-4/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ae4-key"), body);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM adverse_event_report WHERE event_key = 'EV-IDEM'",
                Integer.class));

        // 异参（不同 severity）：409
        assertEquals(409, exchange(
                "/api/experiments/AE-4/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ae4-key"),
                "{\"eventKey\":\"EV-IDEM\",\"severity\":\"MILD\",\"description\":\"幂等事件\"}")
                .getStatusCode().value());

        // 业务失败不占键：非法严重度失败后，同键换合法参数成功
        String failKey = "ae4-fail-key";
        assertEquals(400, exchange(
                "/api/experiments/AE-4/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", failKey),
                "{\"eventKey\":\"EV-F\",\"severity\":\"BAD\",\"description\":\"x\"}")
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = ?",
                Integer.class, failKey));
        assertEquals(201, exchange(
                "/api/experiments/AE-4/participants/P1/adverse-events", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", failKey),
                "{\"eventKey\":\"EV-F\",\"severity\":\"MILD\",\"description\":\"x\"}")
                .getStatusCode().value());
    }
}
