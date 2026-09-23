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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 揭盲申请终止机制主流程与失败分支：
 * validMinutes 有效期、到期时钟裁决（查询不写库）、撤销、拒绝、终态不可互转、
 * 历史 PENDING 按创建时间+30分钟迁移、历史 APPROVED 不追溯设限、
 * 到期/拒绝后结果不泄露处理代码、重新申请归档旧占位且不复用旧 ID、幂等回放边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnblindLifecycleTest extends AbstractBlindIntegrationTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;

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

    private String apply(String expId, String pid, String key, String body) {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", key), body);
        return resp.getBody();
    }

    private int applyStatus(String expId, String pid, String key, String body) {
        return exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", key), body)
                .getStatusCode().value();
    }

    private long allocationId(String expId, String pid) {
        return jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = ? AND participant_id = ?",
                Long.class, expId, pid);
    }

    @Test
    void validMinutes_default30_andRange1to60_expiryRecorded() throws Exception {
        setupParticipant("L-1", "P1", "l1");

        // 默认 30 分钟
        ResponseEntity<String> def = exchange(
                "/api/experiments/L-1/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l1-apply-default"),
                "{\"reason\":\"默认有效期\"}");
        assertEquals(201, def.getStatusCode().value());
        JsonNode body = json(def);
        assertEquals(30, body.path("validMinutes").asInt());
        assertEquals(T0 + 30 * MINUTE, body.path("expiresAt").asLong());
        assertEquals("PENDING", body.path("status").asText());
        assertTrue(body.path("terminatedAt").isNull());

        // 边界 1、60 合法；0、61 非法（P2~P5 为各自独立参与者，先登记再申请）
        for (String pid : new String[]{"P2", "P3", "P4", "P5"}) {
            assertEquals(201, exchange(
                    "/api/experiments/L-1/participants/" + pid + "/allocations",
                    HttpMethod.POST,
                    headers("coord-1", "COORDINATOR", "l1-alloc-" + pid), null)
                    .getStatusCode().value());
        }
        assertEquals(201, applyStatus("L-1", "P2", "l1-apply-1",
                "{\"reason\":\"一分钟\",\"validMinutes\":1}"));
        assertEquals(201, applyStatus("L-1", "P3", "l1-apply-60",
                "{\"reason\":\"六十分钟\",\"validMinutes\":60}"));
        assertEquals(400, applyStatus("L-1", "P4", "l1-apply-0",
                "{\"reason\":\"非法\",\"validMinutes\":0}"));
        assertEquals(400, applyStatus("L-1", "P5", "l1-apply-61",
                "{\"reason\":\"非法\",\"validMinutes\":61}"));
    }

    @Test
    void expiredPending_queryShowsExpired_withoutWritingDb() throws Exception {
        setupParticipant("L-2", "P1", "l2");
        String ubId = json(exchange(
                "/api/experiments/L-2/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l2-apply"),
                "{\"reason\":\"短时\",\"validMinutes\":1}")).path("requestId").asText();

        clock.advance(MINUTE - 1);
        assertEquals("PENDING", json(exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null)).path("status").asText());

        // 到达 expiresAt：查询展示 EXPIRED
        clock.advance(1);
        JsonNode expired = json(exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("EXPIRED", expired.path("status").asText());
        assertEquals(T0 + MINUTE, expired.path("expiresAt").asLong());
        assertEquals(T0 + MINUTE, expired.path("terminatedAt").asLong(),
                "到期时间固定为 expiresAt");
        assertTrue(expired.path("terminatedActor").isNull(), "到期不得伪造人工处理人");
        assertTrue(expired.path("terminateReason").isNull());
        assertFalse(expired.has("treatment"));

        // 普通查询不写库：数据库行仍为 PENDING 占位
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND pending_allocation_id IS NOT NULL",
                Long.class, ubId));
    }

    @Test
    void approveRejectCancel_afterExpiry_allConflict_andNoTreatmentResidue() {
        setupParticipant("L-3", "P1", "l3");
        applyStatus("L-3", "P1", "l3-apply", "{\"reason\":\"x\",\"validMinutes\":1}");
        String ubId = jdbc.queryForObject(
                "SELECT id FROM unblind_request WHERE allocation_id = ?",
                String.class, allocationId("L-3", "P1"));
        clock.advance(MINUTE);

        // 到期一律 409，不得变成批准
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l3-approve-late"), null).getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l3-reject-late"),
                "{\"rejectReason\":\"晚了\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l3-cancel-late"), null)
                .getStatusCode().value());

        // 失败不残留盲底，库内仍为 PENDING（到期不写库），treatment 为空
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId));
        assertNull(jdbc.queryForObject(
                "SELECT treatment FROM unblind_request WHERE id = ?", String.class, ubId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id IN "
                        + "('l3-approve-late','l3-reject-late','l3-cancel-late')", Integer.class),
                "失败不占幂等键");
    }

    @Test
    void cancel_byApplicant_only_andTerminalStatesNotConvertible() throws Exception {
        setupParticipant("L-4", "P1", "l4");
        String ubId = json(exchange(
                "/api/experiments/L-4/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l4-apply"),
                "{\"reason\":\"撤销用\"}")).path("requestId").asText();
        clock.advance(1000);

        // 非申请人（另一名协调员）撤销：403
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST,
                headers("coord-other", "COORDINATOR", "l4-cancel-other"), null)
                .getStatusCode().value());
        // REVIEWER 角色不能调撤销接口：403，且先于幂等
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l4-cancel-reviewer"), null)
                .getStatusCode().value());

        // 申请人本人撤销成功
        ResponseEntity<String> cancelled = exchange(
                "/api/unblind-requests/" + ubId + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l4-cancel"), null);
        assertEquals(200, cancelled.getStatusCode().value());
        JsonNode body = json(cancelled);
        assertEquals("CANCELLED", body.path("status").asText());
        assertEquals("coord-1", body.path("terminatedActor").asText());
        assertEquals(T0 + 1000, body.path("terminatedAt").asLong());
        assertTrue(body.path("terminateReason").isNull());

        // 终态不可互转：撤销后不能再批准/拒绝/撤销
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l4-approve-after"), null).getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l4-reject-after"),
                "{\"rejectReason\":\"晚了\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l4-cancel-twice"), null)
                .getStatusCode().value());

        // 撤销后可重新申请，生成新 ID，旧历史保留 CANCELLED
        ResponseEntity<String> reapply = exchange(
                "/api/experiments/L-4/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l4-reapply"),
                "{\"reason\":\"重新申请\"}");
        assertEquals(201, reapply.getStatusCode().value());
        String newId = json(reapply).path("requestId").asText();
        assertFalse(ubId.equals(newId), "重新申请不复用旧 ID");
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId));
    }

    @Test
    void reject_byOtherReviewer_requiresReason_selfRejectForbidden_resultStaysHidden()
            throws Exception {
        setupParticipant("L-5", "P1", "l5");
        String ubId = json(exchange(
                "/api/experiments/L-5/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l5-apply"),
                "{\"reason\":\"待拒\"}")).path("requestId").asText();
        clock.advance(2000);

        // 空原因 400
        assertEquals(400, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l5-reject-blank"),
                "{\"rejectReason\":\"\"}").getStatusCode().value());
        // 申请人不能自拒（同一 actorId 换 REVIEWER 头）
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST,
                headers("coord-1", "REVIEWER", "l5-reject-self"),
                "{\"rejectReason\":\"自己拒\"}").getStatusCode().value());
        // COORDINATOR 不能调拒绝接口
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST,
                headers("coord-2", "COORDINATOR", "l5-reject-coord"),
                "{\"rejectReason\":\"角色不对\"}").getStatusCode().value());

        // 另一名 REVIEWER 拒绝成功
        ResponseEntity<String> rejected = exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l5-reject"),
                "{\"rejectReason\":\"材料不全\"}");
        assertEquals(200, rejected.getStatusCode().value());
        JsonNode body = json(rejected);
        assertEquals("REJECTED", body.path("status").asText());
        assertEquals("材料不全", body.path("terminateReason").asText());
        assertEquals("rev-2", body.path("terminatedActor").asText());
        assertEquals(T0 + 2000, body.path("terminatedAt").asLong());
        assertFalse(body.has("treatment"));

        // 终态不可互转：拒绝后批准/撤销/再次拒绝均 409
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("rev-3", "REVIEWER", "l5-approve-after"), null).getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l5-cancel-after"), null)
                .getStatusCode().value());

        // 拒绝后结果查询仍不泄露处理代码：申请人 409，其他人 403
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("rev-2", "REVIEWER", null), null).getStatusCode().value());
        assertNull(jdbc.queryForObject(
                "SELECT treatment FROM unblind_request WHERE id = ?", String.class, ubId),
                "拒绝不得写入盲底");

        // 申请视图：申请人、拒绝人可见；无关 REVIEWER 403
        assertEquals(200, exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("rev-2", "REVIEWER", null), null).getStatusCode().value());
        assertEquals(403, exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("rev-3", "REVIEWER", null), null).getStatusCode().value());
    }

    @Test
    void expiredResultQuery_applicantConflict_othersForbidden() {
        setupParticipant("L-6", "P1", "l6");
        applyStatus("L-6", "P1", "l6-apply", "{\"reason\":\"x\",\"validMinutes\":1}");
        String ubId = jdbc.queryForObject(
                "SELECT id FROM unblind_request WHERE allocation_id = ?",
                String.class, allocationId("L-6", "P1"));
        clock.advance(MINUTE);

        // 到期后结果查询：申请人 409，非申请人 403
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("rev-2", "REVIEWER", null), null).getStatusCode().value());
        assertEquals(404, exchange("/api/unblind-requests/UB-MISSING/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null).getStatusCode().value());
    }

    @Test
    void reapplyAfterExpiry_archivesOldPlaceholder_inSameTransactionWithNewId() throws Exception {
        setupParticipant("L-7", "P1", "l7");
        String oldId = mapper.readTree(apply("L-7", "P1", "l7-apply-1",
                "{\"reason\":\"首次\",\"validMinutes\":1}")).path("requestId").asText();
        clock.advance(MINUTE);

        // 新申请在同一事务归档旧过期占位
        ResponseEntity<String> second = exchange(
                "/api/experiments/L-7/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l7-apply-2"),
                "{\"reason\":\"再次申请\"}");
        assertEquals(201, second.getStatusCode().value());
        String newId = json(second).path("requestId").asText();
        assertFalse(oldId.equals(newId));

        // 旧申请已落库为 EXPIRED：终态时间固定 expiresAt，无处理人
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, oldId));
        assertEquals(T0 + MINUTE, jdbc.queryForObject(
                "SELECT terminated_at FROM unblind_request WHERE id = ?", Long.class, oldId));
        assertNull(jdbc.queryForObject(
                "SELECT terminated_actor FROM unblind_request WHERE id = ?", String.class, oldId));
        assertNull(jdbc.queryForObject(
                "SELECT pending_allocation_id FROM unblind_request WHERE id = ?",
                Long.class, oldId));

        // 同一参与者始终至多一份有效待审
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? AND status = 'PENDING'",
                Long.class, allocationId("L-7", "P1")));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ?", Long.class,
                allocationId("L-7", "P1")), "旧申请历史不可覆盖");

        // 旧 ID 查询展示 EXPIRED 历史快照
        assertEquals("EXPIRED", json(exchange("/api/unblind-requests/" + oldId, HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null)).path("status").asText());

        // 重放旧申请键：只返回原创建快照（旧 ID、PENDING 快照），不重占位置
        ResponseEntity<String> replayOld = exchange(
                "/api/experiments/L-7/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l7-apply-1"),
                "{\"reason\":\"首次\",\"validMinutes\":1}");
        assertEquals(201, replayOld.getStatusCode().value());
        assertEquals(oldId, json(replayOld).path("requestId").asText());
        assertEquals("PENDING", json(replayOld).path("status").asText());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? AND status = 'PENDING'",
                Long.class, allocationId("L-7", "P1")),
                "重放旧申请不得重新占位");
    }

    @Test
    void legacyPending_nullExpiresAt_usesCreatedAtPlus30_andCanBeArchived() throws Exception {
        setupParticipant("L-8", "P1", "l8");
        long allocId = allocationId("L-8", "P1");
        // 模拟历史 PENDING：无 expires_at 列值
        jdbc.update("INSERT INTO unblind_request (id, experiment_id, participant_id, "
                        + "allocation_id, reason, applicant_actor, reviewer_actor, status, "
                        + "treatment, created_at, reviewed_at, pending_allocation_id, "
                        + "valid_minutes, expires_at) VALUES "
                        + "('UB-LEGACY', 'L-8', 'P1', ?, '历史申请', 'coord-1', NULL, 'PENDING', "
                        + "NULL, ?, NULL, ?, 30, NULL)",
                allocId, T0, allocId);

        // 29 分钟仍 PENDING
        clock.advance(29 * MINUTE);
        assertEquals(200, exchange("/api/unblind-requests/UB-LEGACY", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = 'UB-LEGACY'", String.class));

        // 30 分钟到期：查询展示 EXPIRED
        clock.advance(MINUTE);
        ResponseEntity<String> view = exchange("/api/unblind-requests/UB-LEGACY",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, view.getStatusCode().value());
        JsonNode node = json(view);
        assertEquals("EXPIRED", node.path("status").asText());
        assertEquals(T0 + 30 * MINUTE, node.path("expiresAt").asLong());

        // 到期批准 409
        assertEquals(409, exchange("/api/unblind-requests/UB-LEGACY/approval", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l8-late-approve"), null).getStatusCode().value());

        // 重新申请归档历史占位
        assertEquals(201, applyStatus("L-8", "P1", "l8-reapply", "{\"reason\":\"新申请\"}"));
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = 'UB-LEGACY'", String.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? AND status = 'PENDING'",
                Long.class, allocId));
    }

    @Test
    void legacyApproved_notRetroactivelyLimited_resultStillAvailable() throws Exception {
        setupParticipant("L-9", "P1", "l9");
        long allocId = allocationId("L-9", "P1");
        // 模拟历史 APPROVED：无 expires_at，时间远超 30 分钟前
        jdbc.update("INSERT INTO unblind_request (id, experiment_id, participant_id, "
                        + "allocation_id, reason, applicant_actor, reviewer_actor, status, "
                        + "treatment, created_at, reviewed_at, pending_allocation_id, "
                        + "valid_minutes, expires_at) VALUES "
                        + "('UB-OLD-OK', 'L-9', 'P1', ?, '历史批准', 'coord-1', 'rev-2', "
                        + "'APPROVED', 'A', ?, ?, NULL, 30, NULL)",
                allocId, T0 - 120 * MINUTE, T0 - 120 * MINUTE + MINUTE);

        // 原 APPROVED 结果不追溯设限
        ResponseEntity<String> result = exchange("/api/unblind-requests/UB-OLD-OK/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        assertEquals("A", json(result).path("treatment").asText());
    }

    @Test
    void cancelAndReject_writes_areIdempotent_andFailureFreesKey() throws Exception {
        setupParticipant("L-10", "P1", "l10");
        String ubId1 = json(exchange(
                "/api/experiments/L-10/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l10-apply-1"),
                "{\"reason\":\"撤销幂等\"}")).path("requestId").asText();

        // 撤销：同键同参重放原 200
        ResponseEntity<String> cancel1 = exchange(
                "/api/unblind-requests/" + ubId1 + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l10-cancel-key"), null);
        assertEquals(200, cancel1.getStatusCode().value());
        ResponseEntity<String> cancelReplay = exchange(
                "/api/unblind-requests/" + ubId1 + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l10-cancel-key"), null);
        assertEquals(200, cancelReplay.getStatusCode().value());
        assertEquals(cancel1.getBody(), cancelReplay.getBody());

        // 新申请用于拒绝幂等
        String ubId2 = json(exchange(
                "/api/experiments/L-10/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l10-apply-2"),
                "{\"reason\":\"拒绝幂等\"}")).path("requestId").asText();
        ResponseEntity<String> reject1 = exchange(
                "/api/unblind-requests/" + ubId2 + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l10-reject-key"),
                "{\"rejectReason\":\"原因一\"}");
        assertEquals(200, reject1.getStatusCode().value());
        ResponseEntity<String> rejectReplay = exchange(
                "/api/unblind-requests/" + ubId2 + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l10-reject-key"),
                "{\"rejectReason\":\"原因一\"}");
        assertEquals(200, rejectReplay.getStatusCode().value());
        assertEquals(reject1.getBody(), rejectReplay.getBody());

        // 同键改参（不同拒绝原因）：409
        assertEquals(409, exchange("/api/unblind-requests/" + ubId2 + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "l10-reject-key"),
                "{\"rejectReason\":\"原因二\"}").getStatusCode().value());

        // 失败不占键：键先用于拒绝不存在的申请（404），再对真实申请复用成功
        assertEquals(404, exchange("/api/unblind-requests/UB-NONE/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "l10-free-key"),
                "{\"rejectReason\":\"不存在\"}").getStatusCode().value());
        String ubId3 = json(exchange(
                "/api/experiments/L-10/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "l10-apply-3"),
                "{\"reason\":\"再拒一次\"}")).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId3 + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "l10-free-key"),
                "{\"rejectReason\":\"真实目标\"}").getStatusCode().value());
    }
}
