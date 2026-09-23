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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 揭盲终止机制：撤销、拒绝、到期裁决的主流程与失败分支。
 * 覆盖：validMinutes 校验、到期按时钟展示不落库、到期后裁决 409、重新申请同事务归档旧占位、
 * 历史 PENDING 空 expires_at 按创建时间 +30 分钟、终态不可互转、结果查询不泄露盲底、
 * 新接口幂等与失败不占键、权限先于幂等。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnblindTerminationTest extends AbstractBlindIntegrationTest {

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

    private void setupParticipant(String expId, String prefix) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", prefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/PA/allocations",
                HttpMethod.POST,
                headers("coord-1", "COORDINATOR", prefix + "-alloc"), null)
                .getStatusCode().value());
    }

    private String apply(String expId, String requestId, String body) throws Exception {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/participants/PA/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", requestId), body);
        assertEquals(201, resp.getStatusCode().value(), resp.getBody());
        return json(resp).path("requestId").asText();
    }

    // ---------------- 撤销 ----------------

    @Test
    void applicant_canCancelOwnPending_andOtherActorsForbidden() throws Exception {
        setupParticipant("T-1", "t1");
        String ubId = apply("T-1", "t1-apply", "{\"reason\":\"核对\",\"validMinutes\":30}");

        // REVIEWER 不能撤销（角色校验先于幂等）
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t1-cancel-by-reviewer"), null)
                .getStatusCode().value());
        // 另一名 COORDINATOR 不能撤销他人申请
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-2", "COORDINATOR", "t1-cancel-other"), null)
                .getStatusCode().value());
        // 申请人本人以 REVIEWER 头访问：角色不足 403（权限先于幂等）
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-1", "REVIEWER", "t1-cancel-self-reviewer"), null)
                .getStatusCode().value());

        // 申请人本人撤销成功
        clock.advance(5_000L);
        ResponseEntity<String> cancelled = exchange(
                "/api/unblind-requests/" + ubId + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t1-cancel"), null);
        assertEquals(200, cancelled.getStatusCode().value());
        JsonNode body = json(cancelled);
        assertEquals("CANCELLED", body.path("status").asText());
        assertEquals("coord-1", body.path("handlerActor").asText());
        assertEquals("申请人主动撤销", body.path("terminalReason").asText());
        assertEquals(1_700_000_005_000L, body.path("terminatedAt").asLong());
        assertFalse(body.has("treatment"), "撤销视图不得包含处理代码");

        // 终态不可再撤销/批准/拒绝
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t1-cancel-again"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t1-approve-after-cancel"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t1-reject-after-cancel"),
                "{\"reason\":\"迟了\"}").getStatusCode().value());

        // 撤销后申请人查结果仍 409，且数据库未写入盲底
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
        assertNull(jdbc.queryForObject(
                "SELECT treatment FROM unblind_request WHERE id = ?", String.class, ubId));
        assertNull(jdbc.queryForObject(
                "SELECT pending_allocation_id FROM unblind_request WHERE id = ?",
                Long.class, ubId), "终态必须释放待审占位");
    }

    // ---------------- 拒绝 ----------------

    @Test
    void anotherReviewer_canRejectWithReason_selfRejectAndBlankRejected() throws Exception {
        setupParticipant("T-2", "t2");
        String ubId = apply("T-2", "t2-apply", "{\"reason\":\"核对\"}");

        // 拒绝原因缺失/空白：400
        assertEquals(400, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t2-reject-blank"),
                "{\"reason\":\"\"}").getStatusCode().value());
        // COORDINATOR 不能拒绝：403（角色先于幂等）
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t2-reject-by-coord"),
                "{\"reason\":\"x\"}").getStatusCode().value());
        // 申请人不能自拒（同 actorId 以 REVIEWER 身份）：403
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("coord-1", "REVIEWER", "t2-reject-self"),
                "{\"reason\":\"自己拒自己\"}").getStatusCode().value());

        clock.advance(5_000L);
        ResponseEntity<String> rejected = exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-3", "REVIEWER", "t2-reject"),
                "{\"reason\":\"材料不完整\"}");
        assertEquals(200, rejected.getStatusCode().value());
        JsonNode body = json(rejected);
        assertEquals("REJECTED", body.path("status").asText());
        assertEquals("rev-3", body.path("handlerActor").asText());
        assertEquals("材料不完整", body.path("terminalReason").asText());
        assertEquals(1_700_000_005_000L, body.path("terminatedAt").asLong());
        assertTrue(body.path("reviewerActor").isNull(), "拒绝不写批准人");
        assertFalse(body.has("treatment"));

        // 拒绝人可查看申请状态；终态不可再批准
        assertEquals(200, exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("rev-3", "REVIEWER", null), null).getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t2-approve-after-reject"), null)
                .getStatusCode().value());
        assertNull(jdbc.queryForObject(
                "SELECT treatment FROM unblind_request WHERE id = ?", String.class, ubId),
                "拒绝不得残留盲底");
    }

    // ---------------- 到期 ----------------

    @Test
    void expiry_isShownByQueryClock_notPersisted_andAdjudicationAfterExpiryConflicts()
            throws Exception {
        setupParticipant("T-3", "t3");
        String ubId = apply("T-3", "t3-apply", "{\"reason\":\"紧急核对\",\"validMinutes\":1}");
        long createdAt = 1_700_000_000_000L;
        long expiresAt = createdAt + 60_000L;

        // 未到期仍 PENDING
        clock.setTime(expiresAt - 1);
        JsonNode before = json(exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("PENDING", before.path("status").asText());
        assertEquals(expiresAt, before.path("expiresAt").asLong());

        // 到达 expiresAt：普通查询展示 EXPIRED，但不写库
        clock.setTime(expiresAt);
        JsonNode expiredView = json(exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("EXPIRED", expiredView.path("status").asText());
        assertEquals(expiresAt, expiredView.path("expiresAt").asLong());
        assertEquals(expiresAt, expiredView.path("terminatedAt").asLong(),
                "到期时间固定为 expiresAt");
        assertTrue(expiredView.path("handlerActor").isNull(), "到期不伪造人工处理人");
        assertEquals("申请已过期", expiredView.path("terminalReason").asText());
        assertFalse(expiredView.has("treatment"));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId),
                "普通到期查询不得写库");

        // 到期后批准/拒绝/撤销均 409，不得变成批准
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t3-approve-late"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t3-reject-late"),
                "{\"reason\":\"晚了\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t3-cancel-late"), null)
                .getStatusCode().value());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId),
                "到期后的失败裁决不得落库任何终态");
        assertNull(jdbc.queryForObject(
                "SELECT treatment FROM unblind_request WHERE id = ?", String.class, ubId),
                "失败裁决不得残留盲底");

        // 到期/拒绝后的结果查询：非申请人 403、申请人 409
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("rev-2", "REVIEWER", null), null)
                .getStatusCode().value());
        ResponseEntity<String> applicantResult = exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null);
        assertEquals(409, applicantResult.getStatusCode().value());
        assertFalse(applicantResult.getBody().contains("treatment"),
                "409 响应不得泄露处理代码字段");

        // 未参与处理的无关 REVIEWER 查询申请状态：403
        assertEquals(403, exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("rev-9", "REVIEWER", null), null).getStatusCode().value());
    }

    @Test
    void reapplyAfterExpiry_archivesOldPlaceholderInSameTx_withNewId_andAtMostOnePending()
            throws Exception {
        setupParticipant("T-4", "t4");
        String oldId = apply("T-4", "t4-apply-1", "{\"reason\":\"首次\",\"validMinutes\":1}");
        long expiresAt = 1_700_000_000_000L + 60_000L;
        clock.setTime(expiresAt + 1_000L);

        // 到期后重新申请：新 ID、新 PENDING；旧记录同事务归档，历史不被覆盖
        ResponseEntity<String> second = exchange(
                "/api/experiments/T-4/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t4-apply-2"),
                "{\"reason\":\"再次申请\",\"validMinutes\":30}");
        assertEquals(201, second.getStatusCode().value());
        String newId = json(second).path("requestId").asText();
        assertNotEquals(oldId, newId, "重新申请不得复用旧 ID");
        assertEquals("PENDING", json(second).path("status").asText());
        assertEquals(1_700_000_061_000L, json(second).path("createdAt").asLong());

        // 旧记录：EXPIRED 已落库，终止时间固定 expiresAt、无处理人、原因保留
        JsonNode oldView = json(exchange("/api/unblind-requests/" + oldId, HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("EXPIRED", oldView.path("status").asText());
        assertEquals("首次", oldView.path("reason").asText(), "旧申请历史不可覆盖");
        assertEquals(expiresAt, oldView.path("terminatedAt").asLong());
        assertTrue(oldView.path("handlerActor").isNull());
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, oldId));

        // 同一参与者始终至多一份有效待审
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE pending_allocation_id IS NOT NULL",
                Long.class).longValue());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request", Long.class).longValue(),
                "新旧两条历史均保留");

        // 新申请可被正常批准，批准后旧记录仍为 EXPIRED（终态不可互转）
        assertEquals(200, exchange("/api/unblind-requests/" + newId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t4-approve-new"), null)
                .getStatusCode().value());
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, oldId));
    }

    @Test
    void approvedResult_isNotRetroactivelyExpired_afterExpiresAt() throws Exception {
        setupParticipant("T-5", "t5");
        String ubId = apply("T-5", "t5-apply", "{\"reason\":\"核对\",\"validMinutes\":1}");
        long expiresAt = 1_700_000_000_000L + 60_000L;
        clock.setTime(expiresAt - 1_000L);
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t5-approve"), null)
                .getStatusCode().value());
        // 远超有效期：已批准结果不追溯设限，申请人仍可查到处理代码
        clock.setTime(expiresAt + 10 * 60_000L);
        JsonNode request = json(exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("APPROVED", request.path("status").asText());
        ResponseEntity<String> result = exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        assertTrue(json(result).path("treatment").asText().matches("[AB]"));
    }

    // ---------------- 历史数据 ----------------

    @Test
    void legacyPendingWithNullExpiresAt_usesCreatedAtPlus30Minutes() throws Exception {
        setupParticipant("T-6", "t6");
        long allocationId = jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = 'T-6' AND participant_id = 'PA'",
                Long.class);
        long legacyCreated = 1_700_000_000_000L - 31 * 60_000L;
        jdbc.update("INSERT INTO unblind_request (id, experiment_id, participant_id, "
                        + "allocation_id, reason, applicant_actor, status, created_at, "
                        + "pending_allocation_id) VALUES (?, 'T-6', 'PA', ?, '历史申请', "
                        + "'coord-1', 'PENDING', ?, ?)",
                "UB-LEGACY-1", allocationId, legacyCreated, allocationId);

        // 历史 PENDING 按创建时间 + 30 分钟视为已到期
        JsonNode view = json(exchange("/api/unblind-requests/UB-LEGACY-1", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("EXPIRED", view.path("status").asText());
        assertEquals(legacyCreated + 30 * 60_000L, view.path("expiresAt").asLong());
        assertEquals(legacyCreated + 30 * 60_000L, view.path("terminatedAt").asLong());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = 'UB-LEGACY-1'", String.class),
                "普通查询不写库");

        // 历史过期申请可被重新申请归档并创建新 PENDING
        ResponseEntity<String> reapply = exchange(
                "/api/experiments/T-6/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t6-reapply"),
                "{\"reason\":\"重新申请\"}");
        assertEquals(201, reapply.getStatusCode().value());
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = 'UB-LEGACY-1'", String.class));
        assertEquals(legacyCreated + 30 * 60_000L, jdbc.queryForObject(
                "SELECT expires_at FROM unblind_request WHERE id = 'UB-LEGACY-1'",
                Long.class).longValue(), "归档时回填到期时刻（创建时间 + 30 分钟）");
    }

    // ---------------- 参数与 404 ----------------

    @Test
    void invalidValidMinutes_isBadRequest_andUnknownRequestIsNotFound() {
        setupParticipant("T-7", "t7");
        assertEquals(400, exchange(
                "/api/experiments/T-7/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t7-apply-0"),
                "{\"reason\":\"x\",\"validMinutes\":0}").getStatusCode().value());
        assertEquals(400, exchange(
                "/api/experiments/T-7/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t7-apply-61"),
                "{\"reason\":\"x\",\"validMinutes\":61}").getStatusCode().value());
        // 400 失败不占键：同一 X-Request-Id 换成合法参数可成功
        assertEquals(201, exchange(
                "/api/experiments/T-7/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t7-apply-0"),
                "{\"reason\":\"x\",\"validMinutes\":1}").getStatusCode().value());

        // 不存在的申请：裁决与查询 404
        assertEquals(404, exchange("/api/unblind-requests/UB-NONE/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t7-approve-missing"), null)
                .getStatusCode().value());
        assertEquals(404, exchange("/api/unblind-requests/UB-NONE/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t7-reject-missing"),
                "{\"reason\":\"x\"}").getStatusCode().value());
        assertEquals(404, exchange("/api/unblind-requests/UB-NONE/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t7-cancel-missing"), null)
                .getStatusCode().value());
        assertEquals(404, exchange("/api/unblind-requests/UB-NONE", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null).getStatusCode().value());
    }

    // ---------------- 幂等 ----------------

    @Test
    void rejectAndCancel_areIdempotent_andFailuresDoNotConsumeKey() throws Exception {
        setupParticipant("T-8", "t8");
        String ubId = apply("T-8", "t8-apply", "{\"reason\":\"核对\"}");

        // 拒绝：同键同参重放原 200，不重复落终态
        ResponseEntity<String> reject1 = exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-3", "REVIEWER", "t8-reject-key"),
                "{\"reason\":\"原因一致\"}");
        assertEquals(200, reject1.getStatusCode().value());
        ResponseEntity<String> rejectReplay = exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-3", "REVIEWER", "t8-reject-key"),
                "{\"reason\":\"原因一致\"}");
        assertEquals(200, rejectReplay.getStatusCode().value());
        assertEquals(reject1.getBody(), rejectReplay.getBody());

        // 同键换原因：异参 409
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-3", "REVIEWER", "t8-reject-key"),
                "{\"reason\":\"原因被改\"}").getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND status = 'REJECTED' "
                        + "AND handler_actor = 'rev-3'", Integer.class, ubId));

        // 撤销/批准的幂等放在另一条申请：业务失败（对已拒绝申请撤销）不占键
        setupParticipant("T-9", "t9");
        String ubId2 = apply("T-9", "t9-apply", "{\"reason\":\"核对2\"}");
        // 先让 rev-2 批准
        assertEquals(200, exchange("/api/unblind-requests/" + ubId2 + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t9-approve"), null)
                .getStatusCode().value());
        // 对已批准申请撤销：409，失败不占键
        assertEquals(409, exchange("/api/unblind-requests/" + ubId2 + "/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t9-cancel-fail"), null)
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 't9-cancel-fail'",
                Integer.class), "失败不占键");
    }

    @Test
    void reapply_replayOldRequest_returnsOriginalSnapshot_andDoesNotReoccupy() throws Exception {
        setupParticipant("T-10", "t10");
        ResponseEntity<String> first = exchange(
                "/api/experiments/T-10/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t10-apply-key"),
                "{\"reason\":\"首次\",\"validMinutes\":1}");
        assertEquals(201, first.getStatusCode().value());
        String oldId = json(first).path("requestId").asText();

        clock.setTime(1_700_000_000_000L + 61_000L);
        // 同键重放：只返回原创建快照（PENDING 视图），不归档、不重新占位
        ResponseEntity<String> replay = exchange(
                "/api/experiments/T-10/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t10-apply-key"),
                "{\"reason\":\"首次\",\"validMinutes\":1}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(oldId, json(replay).path("requestId").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request", Integer.class),
                "重放旧申请不重新占位、不归档");
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, oldId));
    }
}
