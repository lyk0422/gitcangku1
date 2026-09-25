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

/**
 * 紧急揭盲通道：URGENT_REVIEW 下 REVIEWER 直接揭盲，跳过常规申请/批准；
 * eventKey 须对应 SEVERE 报告否则 422；退组 409；不受 CLOSED 限制；
 * 与常规通道互不阻塞但同一分配只成功揭盲一次；幂等回放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmergencyUnblindTest extends AbstractBlindIntegrationTest {

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

    private void setupExperimentWithParticipant(String expId, String requestIdPrefix) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", requestIdPrefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/PA/allocations",
                HttpMethod.POST,
                headers("coord-1", "COORDINATOR", requestIdPrefix + "-alloc"), null)
                .getStatusCode().value());
    }

    private void reportSevere(String expId, String requestId, String eventKey) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/PA/adverse-events",
                HttpMethod.POST, headers("rev-1", "REVIEWER", requestId),
                "{\"eventKey\":\"" + eventKey + "\",\"severity\":\"SEVERE\","
                        + "\"description\":\"重度不良事件\"}").getStatusCode().value());
    }

    private ResponseEntity<String> emergency(String expId, String actor, String role,
                                             String requestId, String eventKey, String reason) {
        return exchange("/api/experiments/" + expId + "/participants/PA/emergency-unblind",
                HttpMethod.POST, headers(actor, role, requestId),
                "{\"eventKey\":\"" + eventKey + "\",\"reason\":\"" + reason + "\"}");
    }

    private String dbTreatment(String expId) {
        List<String> t = jdbc.queryForList(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id "
                        + "AND s.block_no = a.block_no AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = ? AND a.participant_id = 'PA'",
                String.class, expId);
        return t.isEmpty() ? null : t.get(0);
    }

    @Test
    void emergencyUnblind_fullFlow_skipsRegularApproval() throws Exception {
        setupExperimentWithParticipant("EM-1", "em1");
        reportSevere("EM-1", "em1-sev", "EV-SEV");
        String expectedTreatment = dbTreatment("EM-1");

        // 无常规申请，REVIEWER 直接紧急揭盲
        clock.advance(5_000L);
        ResponseEntity<String> response = emergency("EM-1", "rev-9", "REVIEWER",
                "em1-emg", "EV-SEV", "受试者安全需要立即揭盲");
        assertEquals(201, response.getStatusCode().value());
        JsonNode body = json(response);
        String ubId = body.path("requestId").asText();
        assertEquals("EMERGENCY", body.path("requestType").asText());
        assertEquals("APPROVED", body.path("status").asText());
        assertEquals("rev-9", body.path("applicantActor").asText());
        assertEquals("rev-9", body.path("reviewerActor").asText());
        assertEquals("EV-SEV", body.path("eventKey").asText());
        assertFalse(body.has("treatment"), "响应视图不得直接包含处理代码");

        // URGENT_REVIEW 标记已关闭，分配已揭盲
        assertEquals("N", jdbc.queryForObject(
                "SELECT urgent_review FROM allocation WHERE experiment_id = 'EM-1' "
                        + "AND participant_id = 'PA'", String.class));
        assertNotNull(jdbc.queryForObject(
                "SELECT unblinded_at FROM allocation WHERE experiment_id = 'EM-1' "
                        + "AND participant_id = 'PA'", Long.class));
        assertEquals("[]", exchange("/api/experiments/EM-1/urgent-reviews",
                HttpMethod.GET, headers("rev-1", "REVIEWER", null), null).getBody());

        // 不可变记录：类型 EMERGENCY，与常规揭盲共用存储与查询权限
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND request_type = 'EMERGENCY' "
                        + "AND status = 'APPROVED' AND event_key = 'EV-SEV'", Integer.class, ubId));

        // 提交紧急揭盲的 REVIEWER（即申请人）可查结果，处理代码与库内盲底一致
        ResponseEntity<String> result = exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("rev-9", "REVIEWER", null), null);
        assertEquals(200, result.getStatusCode().value());
        assertEquals(expectedTreatment, json(result).path("treatment").asText());
        // 其他人查结果：403
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());

        // 重复紧急揭盲（新 requestId）：已揭盲 409
        assertEquals(409, emergency("EM-1", "rev-9", "REVIEWER",
                "em1-emg-again", "EV-SEV", "再次").getStatusCode().value());
    }

    @Test
    void emergencyUnblind_channelRestrictions() {
        setupExperimentWithParticipant("EM-2", "em2");

        // 未处于 URGENT_REVIEW：409
        assertEquals(409, emergency("EM-2", "rev-1", "REVIEWER",
                "em2-no-urgent", "EV-X", "无标记").getStatusCode().value());

        // MILD 报告不触发 URGENT_REVIEW，紧急揭盲仍 409
        assertEquals(201, exchange("/api/experiments/EM-2/participants/PA/adverse-events",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "em2-mild"),
                "{\"eventKey\":\"EV-MILD\",\"severity\":\"MILD\",\"description\":\"轻度\"}")
                .getStatusCode().value());
        assertEquals(409, emergency("EM-2", "rev-1", "REVIEWER",
                "em2-mild-emg", "EV-MILD", "轻度不可紧急揭盲").getStatusCode().value());

        // SEVERE 报告后：eventKey 不存在 -> 422；eventKey 对应非 SEVERE -> 422
        reportSevere("EM-2", "em2-sev", "EV-SEV");
        assertEquals(422, emergency("EM-2", "rev-1", "REVIEWER",
                "em2-missing-key", "EV-MISSING", "键不存在").getStatusCode().value());
        assertEquals(422, emergency("EM-2", "rev-1", "REVIEWER",
                "em2-mild-key", "EV-MILD", "严重度不符").getStatusCode().value());

        // COORDINATOR 不可紧急揭盲：403（权限先于幂等）
        assertEquals(403, emergency("EM-2", "coord-1", "COORDINATOR",
                "em2-coord", "EV-SEV", "协调员不可").getStatusCode().value());

        // 422/409/403 失败后，正常紧急揭盲仍可成功（失败不占键、不改变状态）
        assertEquals(201, emergency("EM-2", "rev-1", "REVIEWER",
                "em2-ok", "EV-SEV", "正当紧急揭盲").getStatusCode().value());
    }

    @Test
    void emergencyUnblind_withdrawnAllocation_rejected409() {
        setupExperimentWithParticipant("EM-3", "em3");
        reportSevere("EM-3", "em3-sev", "EV-SEV");
        // 退组后不可紧急揭盲
        assertEquals(200, exchange("/api/experiments/EM-3/participants/PA/withdrawal",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "em3-withdraw"), null)
                .getStatusCode().value());
        assertEquals(409, emergency("EM-3", "rev-1", "REVIEWER",
                "em3-emg", "EV-SEV", "退组后尝试").getStatusCode().value());
    }

    @Test
    void emergencyUnblind_notBlockedByClosedExperiment() {
        setupExperimentWithParticipant("EM-4", "em4");
        reportSevere("EM-4", "em4-sev", "EV-SEV");
        // 关闭实验不影响紧急揭盲
        assertEquals(200, exchange("/api/experiments/EM-4/close", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "em4-close"), null).getStatusCode().value());
        assertEquals(201, emergency("EM-4", "rev-1", "REVIEWER",
                "em4-emg", "EV-SEV", "关闭后仍可紧急揭盲").getStatusCode().value());
    }

    @Test
    void emergencyAndRegularPaths_doNotBlockButOnlyOneUnblinds() {
        setupExperimentWithParticipant("EM-5", "em5");
        reportSevere("EM-5", "em5-sev", "EV-SEV");

        // 已存在常规待审申请时，紧急揭盲仍可独立执行
        ResponseEntity<String> apply = exchange(
                "/api/experiments/EM-5/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "em5-apply"), "{\"reason\":\"常规核对\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId;
        try {
            ubId = new ObjectMapper().readTree(apply.getBody()).path("requestId").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertEquals(201, emergency("EM-5", "rev-1", "REVIEWER",
                "em5-emg", "EV-SEV", "紧急优先").getStatusCode().value());

        // 紧急先成功：常规批准对已揭盲分配返回 409
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "em5-approve"), null)
                .getStatusCode().value());
        // 常规通道后续申请也返回 409
        assertEquals(409, exchange(
                "/api/experiments/EM-5/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "em5-apply-2"), "{\"reason\":\"再次申请\"}")
                .getStatusCode().value());
    }

    @Test
    void regularApprovalFirst_emergencyReturns409() {
        setupExperimentWithParticipant("EM-6", "em6");
        reportSevere("EM-6", "em6-sev", "EV-SEV");

        // 常规通道先完成揭盲
        ResponseEntity<String> apply = exchange(
                "/api/experiments/EM-6/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "em6-apply"), "{\"reason\":\"常规核对\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId;
        try {
            ubId = new ObjectMapper().readTree(apply.getBody()).path("requestId").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "em6-approve"), null)
                .getStatusCode().value());

        // 紧急通道对已揭盲分配返回 409
        assertEquals(409, emergency("EM-6", "rev-1", "REVIEWER",
                "em6-emg", "EV-SEV", "常规已揭盲").getStatusCode().value());
        // 常规批准后 URGENT_REVIEW 标记也已关闭
        assertEquals("N", jdbc.queryForObject(
                "SELECT urgent_review FROM allocation WHERE experiment_id = 'EM-6' "
                        + "AND participant_id = 'PA'", String.class));
    }

    @Test
    void emergencyUnblind_idempotentReplay() throws Exception {
        setupExperimentWithParticipant("EM-7", "em7");
        reportSevere("EM-7", "em7-sev", "EV-SEV");

        ResponseEntity<String> first = emergency("EM-7", "rev-1", "REVIEWER",
                "em7-key", "EV-SEV", "幂等紧急揭盲");
        assertEquals(201, first.getStatusCode().value());
        // 同键同参重放：返回首次结果，不产生第二条 EMERGENCY 记录
        ResponseEntity<String> replay = emergency("EM-7", "rev-1", "REVIEWER",
                "em7-key", "EV-SEV", "幂等紧急揭盲");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE experiment_id = 'EM-7' "
                        + "AND request_type = 'EMERGENCY'", Integer.class));
        // 同键异参：409
        assertEquals(409, emergency("EM-7", "rev-1", "REVIEWER",
                "em7-key", "EV-SEV", "不同理由").getStatusCode().value());
    }
}
