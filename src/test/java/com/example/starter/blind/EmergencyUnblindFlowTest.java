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
 * 紧急揭盲通道：SEVERE 报告下 REVIEWER 直接揭盲、跳过常规审批；
 * 通道限制（角色/URGENT_REVIEW/eventKey 严重度/已揭盲/退组/CLOSED）；
 * 与常规待审申请并存、同一分配只成功揭盲一次；幂等回放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmergencyUnblindFlowTest extends AbstractBlindIntegrationTest {

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

    private void report(String expId, String pid, String eventKey, String severity,
                        String actor, String role, String reqId) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/adverse-events",
                HttpMethod.POST, headers(actor, role, reqId),
                "{\"eventKey\":\"" + eventKey + "\",\"severity\":\"" + severity
                        + "\",\"description\":\"合成事件\"}")
                .getStatusCode().value());
    }

    private String dbTreatment(String expId, String pid) {
        List<String> t = jdbc.queryForList(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id "
                        + "AND s.block_no = a.block_no AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = ? AND a.participant_id = ?",
                String.class, expId, pid);
        return t.isEmpty() ? null : t.get(0);
    }

    @Test
    void severeEvent_reviewerEmergencyUnblinds_withoutRegularApplication() throws Exception {
        setupParticipant("EM-1", "P1", "em1");
        report("EM-1", "P1", "EV-S", "SEVERE", "coord-1", "COORDINATOR", "em1-report");
        String expectedTreatment = dbTreatment("EM-1", "P1");
        assertEquals("A", expectedTreatment);

        // 没有任何常规待审申请，REVIEWER 直接紧急揭盲
        ResponseEntity<String> emergency = exchange(
                "/api/experiments/EM-1/participants/P1/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em1-emergency"),
                "{\"eventKey\":\"EV-S\",\"reason\":\"严重不良事件需立即获知处理代码\"}");
        assertEquals(200, emergency.getStatusCode().value());
        JsonNode body = json(emergency);
        assertEquals(expectedTreatment, body.path("treatment").asText());
        assertEquals("APPROVED", body.path("status").asText());
        assertEquals("EMERGENCY", body.path("unblindType").asText());
        String emergencyId = body.path("requestId").asText();
        assertTrue(emergencyId.startsWith("UE-"));
        assertEquals(1_700_000_000_000L, body.path("reviewedAt").asLong());

        // 分配终局：已揭盲、URGENT_REVIEW 清除
        assertEquals(0, jdbc.queryForObject(
                "SELECT urgent_review FROM allocation WHERE experiment_id = 'EM-1' "
                        + "AND participant_id = 'P1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT unblinded FROM allocation WHERE experiment_id = 'EM-1' "
                        + "AND participant_id = 'P1'", Integer.class));
        // URGENT_REVIEW 清单不再包含该分配
        assertEquals("[]", exchange(
                "/api/experiments/EM-1/urgent-review-allocations", HttpMethod.GET,
                headers("rev-2", "REVIEWER", null), null).getBody());

        // 报告被标记为已用于揭盲
        assertEquals(emergencyId, jdbc.queryForObject(
                "SELECT unblind_request_id FROM adverse_event_report "
                        + "WHERE event_key = 'EV-S'", String.class));

        // 揭盲记录：EMERGENCY 类型、APPROVED、写入处理代码
        assertEquals("EMERGENCY", jdbc.queryForObject(
                "SELECT unblind_type FROM unblind_request WHERE id = ?",
                String.class, emergencyId));
        assertEquals(expectedTreatment, jdbc.queryForObject(
                "SELECT treatment FROM unblind_request WHERE id = ?",
                String.class, emergencyId));

        // 共用查询权限：仅发起人本人可查结果
        ResponseEntity<String> result = exchange(
                "/api/unblind-requests/" + emergencyId + "/result", HttpMethod.GET,
                headers("rev-2", "REVIEWER", null), null);
        assertEquals(200, result.getStatusCode().value());
        assertEquals(expectedTreatment, json(result).path("treatment").asText());
        assertEquals("EMERGENCY", json(result).path("unblindType").asText());
        assertEquals(403, exchange(
                "/api/unblind-requests/" + emergencyId + "/result", HttpMethod.GET,
                headers("rev-other", "REVIEWER", null), null).getStatusCode().value());
        assertEquals(403, exchange(
                "/api/unblind-requests/" + emergencyId + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null).getStatusCode().value());
        // 申请状态视图带类型且不含处理代码
        ResponseEntity<String> requestView = exchange(
                "/api/unblind-requests/" + emergencyId, HttpMethod.GET,
                headers("rev-2", "REVIEWER", null), null);
        assertEquals(200, requestView.getStatusCode().value());
        assertEquals("EMERGENCY", json(requestView).path("unblindType").asText());
        assertFalse(json(requestView).has("treatment"));
    }

    @Test
    void emergencyChannel_restrictions_422And409And403() {
        setupParticipant("EM-2", "P1", "em2");
        report("EM-2", "P1", "EV-MILD", "MILD", "coord-1", "COORDINATOR", "em2-mild");

        String emergencyPath = "/api/experiments/EM-2/participants/P1/emergency-unblind";

        // 协调员不可紧急揭盲：403（权限先于幂等）
        assertEquals(403, exchange(emergencyPath, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "em2-by-coord"),
                "{\"eventKey\":\"EV-MILD\",\"reason\":\"x\"}").getStatusCode().value());

        // 非 URGENT_REVIEW（仅 MILD）：422
        assertEquals(422, exchange(emergencyPath, HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em2-not-urgent"),
                "{\"eventKey\":\"EV-MILD\",\"reason\":\"x\"}").getStatusCode().value());

        // 制造 URGENT_REVIEW
        report("EM-2", "P1", "EV-SEV", "SEVERE", "coord-1", "COORDINATOR", "em2-sev");

        // eventKey 不存在：422
        assertEquals(422, exchange(emergencyPath, HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em2-no-key"),
                "{\"eventKey\":\"EV-NOPE\",\"reason\":\"x\"}").getStatusCode().value());

        // eventKey 对应报告不是 SEVERE：422
        assertEquals(422, exchange(emergencyPath, HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em2-mild-key"),
                "{\"eventKey\":\"EV-MILD\",\"reason\":\"x\"}").getStatusCode().value());

        // 理由缺失：400
        assertEquals(400, exchange(emergencyPath, HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em2-no-reason"),
                "{\"eventKey\":\"EV-SEV\",\"reason\":\"\"}").getStatusCode().value());

        // 未登记参与者：404
        assertEquals(404, exchange(
                "/api/experiments/EM-2/participants/NOBODY/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em2-nobody"),
                "{\"eventKey\":\"EV-SEV\",\"reason\":\"x\"}").getStatusCode().value());

        // 成功揭盲
        assertEquals(200, exchange(emergencyPath, HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em2-success"),
                "{\"eventKey\":\"EV-SEV\",\"reason\":\"立即揭盲\"}").getStatusCode().value());

        // 已揭盲再紧急揭盲：409
        assertEquals(409, exchange(emergencyPath, HttpMethod.POST,
                headers("rev-3", "REVIEWER", "em2-again"),
                "{\"eventKey\":\"EV-SEV\",\"reason\":\"再试\"}").getStatusCode().value());

        // 已揭盲后再发起常规申请：409
        assertEquals(409, exchange(
                "/api/experiments/EM-2/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "em2-regular-after"),
                "{\"reason\":\"紧急之后常规\"}").getStatusCode().value());

        // 同一 SEVERE 报告复用：409（已揭盲分支先于报告分支）
        assertEquals(409, exchange(emergencyPath, HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em2-reuse-report"),
                "{\"eventKey\":\"EV-SEV\",\"reason\":\"x\"}").getStatusCode().value());
    }

    @Test
    void emergencyUnblind_allowedWhenClosed_blockedWhenWithdrawn() {
        // 场景一：CLOSED 实验仍可紧急揭盲
        setupParticipant("EM-3", "P1", "em3");
        report("EM-3", "P1", "EV-S", "SEVERE", "coord-1", "COORDINATOR", "em3-report");
        assertEquals(200, exchange("/api/experiments/EM-3/close", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "em3-close"), null).getStatusCode().value());
        assertEquals(200, exchange(
                "/api/experiments/EM-3/participants/P1/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em3-emergency"),
                "{\"eventKey\":\"EV-S\",\"reason\":\"已关闭实验的严重事件\"}")
                .getStatusCode().value());

        // 场景二：退组分配不可紧急揭盲
        setupParticipant("EM-4", "P1", "em4");
        report("EM-4", "P1", "EV-S", "SEVERE", "coord-1", "COORDINATOR", "em4-report");
        // 先 SEVERE 再退组：退组不清除 URGENT_REVIEW，但紧急揭盲被拒
        assertEquals(200, exchange(
                "/api/experiments/EM-4/participants/P1/withdrawal", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "em4-withdraw"), null)
                .getStatusCode().value());
        assertEquals(409, exchange(
                "/api/experiments/EM-4/participants/P1/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em4-emergency"),
                "{\"eventKey\":\"EV-S\",\"reason\":\"退组后试图紧急揭盲\"}")
                .getStatusCode().value());
        // 未揭盲
        assertEquals(0, jdbc.queryForObject(
                "SELECT unblinded FROM allocation WHERE experiment_id = 'EM-4' "
                        + "AND participant_id = 'P1'", Integer.class));

        // 场景三：退组后新的 SEVERE 报告（退组分配仍可被报告）也不能紧急揭盲
        report("EM-4", "P1", "EV-S2", "SEVERE", "coord-1", "COORDINATOR", "em4-report-2");
        assertEquals(409, exchange(
                "/api/experiments/EM-4/participants/P1/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em4-emergency-2"),
                "{\"eventKey\":\"EV-S2\",\"reason\":\"x\"}").getStatusCode().value());
    }

    @Test
    void pendingRegularRequest_doesNotBlockEmergency_andRegularApprovalThenConflicts()
            throws Exception {
        setupParticipant("EM-5", "P1", "em5");
        report("EM-5", "P1", "EV-S", "SEVERE", "coord-1", "COORDINATOR", "em5-report");

        // 协调员先发起常规待审申请
        ResponseEntity<String> apply = exchange(
                "/api/experiments/EM-5/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "em5-apply"), "{\"reason\":\"常规申请\"}");
        assertEquals(201, apply.getStatusCode().value());
        String regularId = json(apply).path("requestId").asText();

        // 紧急揭盲独立执行，不被待审申请阻塞
        ResponseEntity<String> emergency = exchange(
                "/api/experiments/EM-5/participants/P1/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em5-emergency"),
                "{\"eventKey\":\"EV-S\",\"reason\":\"紧急优先\"}");
        assertEquals(200, emergency.getStatusCode().value());
        String emergencyId = json(emergency).path("requestId").asText();
        assertEquals("EMERGENCY", json(emergency).path("unblindType").asText());

        // 常规待审申请仍存在且仍为 PENDING（两条路径互不阻塞）
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?",
                String.class, regularId));

        // 后续常规批准：对已揭盲分配 409
        assertEquals(409, exchange(
                "/api/unblind-requests/" + regularId + "/approval", HttpMethod.POST,
                headers("rev-3", "REVIEWER", "em5-approve"), null).getStatusCode().value());

        // 全库只有一条真正揭盲（treatment 非空）的记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE treatment IS NOT NULL",
                Integer.class));
        assertEquals("EMERGENCY", jdbc.queryForObject(
                "SELECT unblind_type FROM unblind_request WHERE treatment IS NOT NULL",
                String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND status = 'PENDING'",
                Integer.class, regularId));
        assertEquals(emergencyId, jdbc.queryForObject(
                "SELECT unblind_request_id FROM adverse_event_report WHERE event_key = 'EV-S'",
                String.class));
    }

    @Test
    void emergencyUnblind_isIdempotent_andAuthPrecedesReplay() throws Exception {
        setupParticipant("EM-6", "P1", "em6");
        report("EM-6", "P1", "EV-S", "SEVERE", "coord-1", "COORDINATOR", "em6-report");

        String body = "{\"eventKey\":\"EV-S\",\"reason\":\"幂等紧急揭盲\"}";
        ResponseEntity<String> first = exchange(
                "/api/experiments/EM-6/participants/P1/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em6-key"), body);
        assertEquals(200, first.getStatusCode().value());

        // 同键同参重放：原 200 结果（而不是已揭盲 409），不产生第二条记录
        ResponseEntity<String> replay = exchange(
                "/api/experiments/EM-6/participants/P1/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em6-key"), body);
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE unblind_type = 'EMERGENCY'",
                Integer.class));

        // 同键换理由：409
        assertEquals(409, exchange(
                "/api/experiments/EM-6/participants/P1/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "em6-key"),
                "{\"eventKey\":\"EV-S\",\"reason\":\"不同理由\"}").getStatusCode().value());

        // 权限先于幂等：协调员用同键请求直接 403，而非回放
        assertEquals(403, exchange(
                "/api/experiments/EM-6/participants/P1/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "COORDINATOR", "em6-key"), body).getStatusCode().value());

        // 失败不占键：对另一参与者用非法 eventKey 失败后，同键可用于合法请求
        setupParticipant("EM-6B", "P2", "em6b");
        report("EM-6B", "P2", "EV-S", "SEVERE", "coord-1", "COORDINATOR", "em6b-report");
        String failKey = "em6-fail-key";
        assertEquals(422, exchange(
                "/api/experiments/EM-6B/participants/P2/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", failKey),
                "{\"eventKey\":\"EV-NOPE\",\"reason\":\"x\"}").getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = ?",
                Integer.class, failKey));
        assertEquals(200, exchange(
                "/api/experiments/EM-6B/participants/P2/emergency-unblind", HttpMethod.POST,
                headers("rev-2", "REVIEWER", failKey),
                "{\"eventKey\":\"EV-S\",\"reason\":\"合法重试\"}").getStatusCode().value());
    }
}
