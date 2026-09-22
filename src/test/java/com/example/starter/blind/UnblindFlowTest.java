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
 * 受控揭盲：申请、批准、结果查询的主流程与失败分支；
 * 关闭/退组不撤销已批准揭盲；处理代码仅在批准后对申请人可见。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnblindFlowTest extends AbstractBlindIntegrationTest {

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

    private String dbTreatment(long allocationId) {
        List<String> t = jdbc.queryForList(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id "
                        + "AND s.block_no = a.block_no AND s.seat_no = a.seat_no "
                        + "WHERE a.id = ?", String.class, allocationId);
        return t.isEmpty() ? null : t.get(0);
    }

    @Test
    void applyApproveAndResult_fullControlledUnblinding() throws Exception {
        setupExperimentWithParticipant("UB-1", "u1");
        long allocationId = jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = 'UB-1' AND participant_id = 'PA'",
                Long.class);
        String expectedTreatment = dbTreatment(allocationId);
        assertEquals("A", expectedTreatment, "第一区组第一席应为 A");

        // 协调员申请揭盲
        ResponseEntity<String> apply = exchange(
                "/api/experiments/UB-1/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u1-apply"),
                "{\"reason\":\"合成测试需要核对处理代码\"}");
        assertEquals(201, apply.getStatusCode().value());
        JsonNode applyBody = json(apply);
        String ubId = applyBody.path("requestId").asText();
        assertEquals("PENDING", applyBody.path("status").asText());
        assertEquals("coord-1", applyBody.path("applicantActor").asText());
        assertFalse(applyBody.has("treatment"), "申请视图不得包含处理代码");

        // 未批准：申请人查询结果 409
        ResponseEntity<String> pendingResult = exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null);
        assertEquals(409, pendingResult.getStatusCode().value());

        // 协调员不能批准（仅 REVIEWER）
        ResponseEntity<String> coordinatorApprove = exchange(
                "/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u1-approve-by-coord"), null);
        assertEquals(403, coordinatorApprove.getStatusCode().value());

        // 申请人本人不能以 REVIEWER 身份批准（必须是另一名 REVIEWER）
        ResponseEntity<String> selfApprove = exchange(
                "/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("coord-1", "REVIEWER", "u1-approve-self"), null);
        assertEquals(403, selfApprove.getStatusCode().value());

        // 另一名 REVIEWER 批准
        clock.advance(10_000L);
        ResponseEntity<String> approved = exchange(
                "/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "u1-approve"), null);
        assertEquals(200, approved.getStatusCode().value());
        JsonNode approvedBody = json(approved);
        assertEquals("APPROVED", approvedBody.path("status").asText());
        assertEquals("rev-2", approvedBody.path("reviewerActor").asText());
        assertFalse(approvedBody.has("treatment"), "批准响应仍不得直接返回处理代码");

        // 重复批准 409
        ResponseEntity<String> twice = exchange(
                "/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("rev-3", "REVIEWER", "u1-approve-twice"), null);
        assertEquals(409, twice.getStatusCode().value());

        // 仅申请人可查结果
        ResponseEntity<String> result = exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        JsonNode resultBody = json(result);
        assertEquals(expectedTreatment, resultBody.path("treatment").asText());
        assertEquals("APPROVED", resultBody.path("status").asText());
        assertEquals(1_700_000_010_000L, resultBody.path("reviewedAt").asLong());

        // 批准人及其他角色查结果：403
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("rev-2", "REVIEWER", null), null)
                .getStatusCode().value());
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("other-coord", "COORDINATOR", null), null)
                .getStatusCode().value());

        // 申请状态查询：非申请人/非批准人 403
        assertEquals(403, exchange("/api/unblind-requests/" + ubId,
                HttpMethod.GET, headers("rev-3", "REVIEWER", null), null)
                .getStatusCode().value());
        assertEquals(200, exchange("/api/unblind-requests/" + ubId,
                HttpMethod.GET, headers("rev-2", "REVIEWER", null), null)
                .getStatusCode().value());
        assertEquals(404, exchange("/api/unblind-requests/UB-MISSING/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
    }

    @Test
    void apply_requiresReason_andOnlyOnePendingPerAllocation() {
        setupExperimentWithParticipant("UB-2", "u2");

        // REVIEWER 不能申请揭盲
        assertEquals(403, exchange(
                "/api/experiments/UB-2/participants/PA/unblind-requests", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "u2-apply-by-reviewer"),
                "{\"reason\":\"x\"}").getStatusCode().value());

        // 原因缺失
        assertEquals(400, exchange(
                "/api/experiments/UB-2/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u2-apply-blank"),
                "{\"reason\":\"\"}").getStatusCode().value());

        // 正常申请
        ResponseEntity<String> first = exchange(
                "/api/experiments/UB-2/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u2-apply-1"),
                "{\"reason\":\"首次申请\"}");
        assertEquals(201, first.getStatusCode().value());

        // 同一分配第二个待审申请 409
        ResponseEntity<String> second = exchange(
                "/api/experiments/UB-2/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u2-apply-2"),
                "{\"reason\":\"再次申请\"}");
        assertEquals(409, second.getStatusCode().value());

        // 为不存在的参与者申请：404
        assertEquals(404, exchange(
                "/api/experiments/UB-2/participants/NOBODY/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u2-apply-missing"),
                "{\"reason\":\"x\"}").getStatusCode().value());
    }

    @Test
    void approvedUnblinding_survivesWithdrawalAndClose() throws Exception {        setupExperimentWithParticipant("UB-3", "u3");
        ResponseEntity<String> apply = exchange(
                "/api/experiments/UB-3/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u3-apply"), "{\"reason\":\"核对\"}");
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "u3-approve"), null)
                .getStatusCode().value());
        String treatmentBefore = json(exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null))
                .path("treatment").asText();

        // 退组
        assertEquals(200, exchange(
                "/api/experiments/UB-3/participants/PA/withdrawal", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u3-withdraw"), null)
                .getStatusCode().value());
        // 关闭实验
        assertEquals(200, exchange("/api/experiments/UB-3/close", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u3-close"), null).getStatusCode().value());

        // 已批准揭盲不撤销，申请人仍可查询到相同处理代码
        ResponseEntity<String> resultAfter = exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, resultAfter.getStatusCode().value());
        assertEquals(treatmentBefore, json(resultAfter).path("treatment").asText());
        assertEquals("APPROVED", json(resultAfter).path("status").asText());
    }

    @Test
    void unblindWrites_areIdempotent_replayReturnsOriginalResult() throws Exception {
        setupExperimentWithParticipant("UB-4", "u4");

        // 申请揭盲：同键同参重放返回同一个申请，不产生第二条申请
        ResponseEntity<String> apply1 = exchange(
                "/api/experiments/UB-4/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u4-apply-key"),
                "{\"reason\":\"幂等申请\"}");
        assertEquals(201, apply1.getStatusCode().value());
        ResponseEntity<String> applyReplay = exchange(
                "/api/experiments/UB-4/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u4-apply-key"),
                "{\"reason\":\"幂等申请\"}");
        assertEquals(201, applyReplay.getStatusCode().value());
        assertEquals(apply1.getBody(), applyReplay.getBody());
        String ubId = json(apply1).path("requestId").asText();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = "
                        + "(SELECT id FROM allocation WHERE experiment_id = 'UB-4' "
                        + "AND participant_id = 'PA')", Integer.class));

        // 同键换原因：异参 409
        ResponseEntity<String> differentReason = exchange(
                "/api/experiments/UB-4/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "u4-apply-key"),
                "{\"reason\":\"不同原因\"}");
        assertEquals(409, differentReason.getStatusCode().value());

        // 批准：同键重放原成功结果（APPROVED），而非重复批准冲突
        ResponseEntity<String> approve1 = exchange(
                "/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "u4-approve-key"), null);
        assertEquals(200, approve1.getStatusCode().value());
        ResponseEntity<String> approveReplay = exchange(
                "/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "u4-approve-key"), null);
        assertEquals(200, approveReplay.getStatusCode().value());
        assertEquals(approve1.getBody(), approveReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND status = 'APPROVED' "
                        + "AND reviewer_actor = 'rev-2'", Integer.class, ubId));
    }
}
