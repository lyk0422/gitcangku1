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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 揭盲申请终止机制：有效期、撤销、拒绝、到期裁决、归档重申、终态互转、
 * 结果查询保密、历史 PENDING 兼容、幂等与真实并发边界。全部用例基于真实 H2
 * （MySQL 兼容模式）与可控时钟，不使用 mock 或 Map 替代数据库边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnblindTerminationTest extends AbstractBlindIntegrationTest {

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

    private void createExperimentWithParticipants(String expId, int participantCount) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        for (int i = 1; i <= participantCount; i++) {
            assertEquals(201, exchange(
                    "/api/experiments/" + expId + "/participants/P" + i + "/allocations",
                    HttpMethod.POST,
                    headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-alloc-" + i),
                    null).getStatusCode().value());
        }
    }

    private String apply(String expId, String pid, String requestId, String body) {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", requestId), body);
        assertEquals(201, resp.getStatusCode().value(), "申请应成功: " + resp.getBody());
        return resp.getBody();
    }

    private long allocationId(String expId, String pid) {
        return jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = ? AND participant_id = ?",
                Long.class, expId, pid);
    }

    // ---------------- 有效期 ----------------

    @Test
    void validMinutes_default30_range1to60_expiresAtFixed() throws Exception {
        createExperimentWithParticipants("VT-1", 4);
        long t0 = clock.nowMillis();

        // 缺省 30 分钟
        JsonNode def = jsonFromApply("VT-1", "P1", "vt1-apply-def",
                "{\"reason\":\"缺省有效期\"}");
        assertEquals(30, def.path("validMinutes").asInt());
        assertEquals(t0 + 30 * MINUTE, def.path("expiresAt").asLong());
        assertEquals("PENDING", def.path("status").asText());
        assertTrue(def.path("terminatedAt").isNull());

        // 边界 1 与 60 合法
        assertEquals(201, applyRaw("VT-1", "P2", "vt1-apply-1",
                "{\"reason\":\"1分钟\",\"validMinutes\":1}").getStatusCode().value());
        assertEquals(201, applyRaw("VT-1", "P3", "vt1-apply-60",
                "{\"reason\":\"60分钟\",\"validMinutes\":60}").getStatusCode().value());

        // 0 与 61 非法：400
        assertEquals(400, applyRaw("VT-1", "P4", "vt1-apply-0",
                "{\"reason\":\"x\",\"validMinutes\":0}").getStatusCode().value());
        assertEquals(400, applyRaw("VT-1", "P4", "vt1-apply-61",
                "{\"reason\":\"x\",\"validMinutes\":61}").getStatusCode().value());

        // 非法参数失败不占键：同一 requestId 改为合法参数后成功
        assertEquals(201, applyRaw("VT-1", "P4", "vt1-apply-0",
                "{\"reason\":\"x\",\"validMinutes\":2}").getStatusCode().value());
    }

    private ResponseEntity<String> applyRaw(String expId, String pid, String requestId, String body) {
        return exchange("/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", requestId), body);
    }

    private JsonNode jsonFromApply(String expId, String pid, String requestId, String body)
            throws Exception {
        return mapper.readTree(apply(expId, pid, requestId, body));
    }

    // ---------------- 撤销 ----------------

    @Test
    void cancel_onlyApplicantSelf_whileUnexpired_recordsTerminal() throws Exception {
        createExperimentWithParticipants("VT-2", 1);
        String ubId = jsonFromApplyBody("VT-2", "P1", "vt2-apply", "{\"reason\":\"r\"}");

        // REVIEWER 不能撤销（仅 COORDINATOR）
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "vt2-cancel-by-rev"), null)
                .getStatusCode().value());
        // 另一名协调员不能撤销他人申请
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-other", "COORDINATOR", "vt2-cancel-other"), null)
                .getStatusCode().value());
        // 申请不存在：404
        assertEquals(404, exchange("/api/unblind-requests/UB-MISSING/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "vt2-cancel-missing"), null)
                .getStatusCode().value());

        clock.advance(5 * MINUTE);
        long cancelTime = clock.nowMillis();
        ResponseEntity<String> ok = exchange(
                "/api/unblind-requests/" + ubId + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "vt2-cancel"), null);
        assertEquals(200, ok.getStatusCode().value());
        JsonNode body = json(ok);
        assertEquals("CANCELLED", body.path("status").asText());
        assertEquals(cancelTime, body.path("terminatedAt").asLong());
        assertTrue(body.path("reviewerActor").isNull(), "撤销不得伪造人工处理人");
        assertFalse(body.has("treatment"));

        // 终态不可互转：再次撤销/批准/拒绝均 409
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "vt2-cancel-again"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "vt2-approve-after-cancel"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "vt2-reject-after-cancel"),
                "{\"reason\":\"x\"}").getStatusCode().value());

        // 撤销后占位已释放、无盲底残留
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? "
                        + "AND (pending_allocation_id IS NOT NULL OR treatment IS NOT NULL)",
                Long.class, ubId));
    }

    private String jsonFromApplyBody(String expId, String pid, String requestId, String body)
            throws Exception {
        return mapper.readTree(apply(expId, pid, requestId, body)).path("requestId").asText();
    }

    // ---------------- 拒绝 ----------------

    @Test
    void reject_anotherReviewerWithReason_terminalAndNoTreatmentLeak() throws Exception {
        createExperimentWithParticipants("VT-3", 1);
        String ubId = jsonFromApplyBody("VT-3", "P1", "vt3-apply", "{\"reason\":\"r\"}");

        // COORDINATOR 角色不能拒绝
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "vt3-reject-coord"),
                "{\"reason\":\"x\"}").getStatusCode().value());
        // 申请人本人即便持 REVIEWER 头也不能自拒（角色校验先于业务，本人回避先于终态）
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("coord-1", "REVIEWER", "vt3-reject-self"),
                "{\"reason\":\"x\"}").getStatusCode().value());
        // 原因空白/缺失：400
        assertEquals(400, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "vt3-reject-blank"),
                "{\"reason\":\"\"}").getStatusCode().value());
        // 不存在：404
        assertEquals(404, exchange("/api/unblind-requests/UB-MISSING/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "vt3-reject-missing"),
                "{\"reason\":\"x\"}").getStatusCode().value());

        clock.advance(2 * MINUTE);
        long rejectTime = clock.nowMillis();
        ResponseEntity<String> ok = exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "vt3-reject"),
                "{\"reason\":\"合成场景：资料不全\"}");
        assertEquals(200, ok.getStatusCode().value());
        JsonNode body = json(ok);
        assertEquals("REJECTED", body.path("status").asText());
        assertEquals("rev-2", body.path("reviewerActor").asText());
        assertEquals("合成场景：资料不全", body.path("rejectReason").asText());
        assertEquals(rejectTime, body.path("terminatedAt").asLong());
        assertTrue(body.path("reviewedAt").isNull());
        assertFalse(body.has("treatment"), "申请视图任何状态都不得返回处理代码");

        // 申请人查询结果：409；非申请人：403；响应不携带处理代码
        ResponseEntity<String> applicantResult = exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null);
        assertEquals(409, applicantResult.getStatusCode().value());
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("rev-2", "REVIEWER", null), null)
                .getStatusCode().value());

        // 库内无处理代码、无待审占位
        assertNull(jdbc.queryForObject(
                "SELECT treatment FROM unblind_request WHERE id = ?", String.class, ubId));
        assertNull(jdbc.queryForObject(
                "SELECT pending_allocation_id FROM unblind_request WHERE id = ?",
                Long.class, ubId));

        // 终态后再批准/拒绝/撤销：409
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-3", "REVIEWER", "vt3-approve-after"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-3", "REVIEWER", "vt3-reject-again"),
                "{\"reason\":\"y\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "vt3-cancel-after"), null)
                .getStatusCode().value());
    }

    // ---------------- 到期 ----------------

    @Test
    void expiry_queryShowsExpiredWithoutWrite_actionsReturn409_andBoundaryInclusive()
            throws Exception {
        createExperimentWithParticipants("VT-4", 1);
        long t0 = clock.nowMillis();
        String ubId = jsonFromApplyBody("VT-4", "P1", "vt4-apply",
                "{\"reason\":\"r\",\"validMinutes\":1}");
        long expiresAt = t0 + MINUTE;

        // 到期前一刻仍 PENDING
        clock.setTime(expiresAt - 1);
        JsonNode before = json(exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("PENDING", before.path("status").asText());

        // 到达 expiresAt 即过期（边界含等于）：普通查询只读展示，不写库
        clock.setTime(expiresAt);
        JsonNode at = json(exchange("/api/unblind-requests/" + ubId, HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("EXPIRED", at.path("status").asText());
        assertEquals(expiresAt, at.path("terminatedAt").asLong(), "过期终态时间固定为 expiresAt");
        assertTrue(at.path("reviewerActor").isNull(), "到期不得伪造人工处理人");
        assertTrue(at.path("rejectReason").isNull());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId),
                "普通查询不得写库");

        // 批准/拒绝/撤销到期申请：一律 409，且不得写入盲底
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "vt4-approve-late"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "vt4-reject-late"),
                "{\"reason\":\"x\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "vt4-cancel-late"), null)
                .getStatusCode().value());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId));
        assertNull(jdbc.queryForObject(
                "SELECT treatment FROM unblind_request WHERE id = ?", String.class, ubId));

        // 申请人到期查结果 409；非申请人 403
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("rev-2", "REVIEWER", null), null)
                .getStatusCode().value());
    }

    @Test
    void expiry_reapplyArchivesOldPlaceholder_sameTransaction_newIdHistoryKept() throws Exception {
        createExperimentWithParticipants("VT-5", 1);
        long t0 = clock.nowMillis();
        String oldId = jsonFromApplyBody("VT-5", "P1", "vt5-apply-old",
                "{\"reason\":\"旧申请\",\"validMinutes\":5}");
        long oldExpiresAt = t0 + 5 * MINUTE;

        clock.setTime(oldExpiresAt + MINUTE);

        // 旧申请同键重放：只返回原创建快照（PENDING 快照），不归档、不重占位
        ResponseEntity<String> replay = exchange(
                "/api/experiments/VT-5/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "vt5-apply-old"),
                "{\"reason\":\"旧申请\",\"validMinutes\":5}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(oldId, json(replay).path("requestId").asText());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ?",
                Long.class, allocationId("VT-5", "P1")));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, oldId));

        // 新 requestId 重申：同事务归档旧过期占位并创建新 PENDING
        ResponseEntity<String> reapplied = exchange(
                "/api/experiments/VT-5/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "vt5-apply-new"),
                "{\"reason\":\"新申请\",\"validMinutes\":10}");
        assertEquals(201, reapplied.getStatusCode().value());
        String newId = json(reapplied).path("requestId").asText();
        assertFalse(oldId.equals(newId), "重新申请不得复用旧 ID");
        assertEquals(10, json(reapplied).path("validMinutes").asInt());

        // 旧行：EXPIRED 落库，终态时间固定 expiresAt，无处理人、无盲底
        JsonNode oldView = json(exchange("/api/unblind-requests/" + oldId, HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("EXPIRED", oldView.path("status").asText());
        assertEquals(oldExpiresAt, oldView.path("terminatedAt").asLong());
        assertTrue(oldView.path("reviewerActor").isNull());
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, oldId));
        assertEquals(oldExpiresAt, jdbc.queryForObject(
                "SELECT terminated_at FROM unblind_request WHERE id = ?", Long.class, oldId));
        assertNull(jdbc.queryForObject(
                "SELECT treatment FROM unblind_request WHERE id = ?", String.class, oldId));

        // 新行：PENDING 且唯一占位；同参与者始终至多一份有效待审
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, newId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? "
                        + "AND pending_allocation_id IS NOT NULL",
                Long.class, allocationId("VT-5", "P1")));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ?",
                Long.class, allocationId("VT-5", "P1")), "旧申请历史必须保留，不可覆盖");

        // 新申请未到期期间，再次重申：409
        assertEquals(409, exchange(
                "/api/experiments/VT-5/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "vt5-apply-third"),
                "{\"reason\":\"又一个\"}").getStatusCode().value());
    }

    @Test
    void legacyPending_withoutValidMinutes_expiresAtCreatedPlus30() throws Exception {
        createExperimentWithParticipants("VT-6", 1);
        long allocId = allocationId("VT-6", "P1");
        long legacyCreated = clock.nowMillis();
        // 模拟历史 PENDING 行：无 valid_minutes/expires_at 列值，以原创建时间+30分钟兜底
        jdbc.update("INSERT INTO unblind_request (id, experiment_id, participant_id, "
                        + "allocation_id, reason, applicant_actor, reviewer_actor, status, "
                        + "treatment, created_at, reviewed_at, pending_allocation_id, "
                        + "valid_minutes, expires_at, reject_reason, terminated_at) "
                        + "VALUES ('UB-LEGACY', 'VT-6', 'P1', ?, '历史申请', 'coord-1', NULL, "
                        + "'PENDING', NULL, ?, NULL, ?, NULL, NULL, NULL, NULL)",
                allocId, legacyCreated, allocId);

        // 到期前：PENDING，兜底展示 30 分钟有效期
        clock.setTime(legacyCreated + 30 * MINUTE - 1);
        JsonNode before = json(exchange("/api/unblind-requests/UB-LEGACY", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals("PENDING", before.path("status").asText());
        assertEquals(30, before.path("validMinutes").asInt());
        assertEquals(legacyCreated + 30 * MINUTE, before.path("expiresAt").asLong());

        // 到达兜底到期时刻：只读展示 EXPIRED
        clock.setTime(legacyCreated + 30 * MINUTE);
        assertEquals("EXPIRED", json(exchange("/api/unblind-requests/UB-LEGACY", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null)).path("status").asText());

        // 重申归档历史行：终态时间固定为创建时间+30分钟
        ResponseEntity<String> reapplied = exchange(
                "/api/experiments/VT-6/participants/P1/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "vt6-reapply"), "{\"reason\":\"新\"}");
        assertEquals(201, reapplied.getStatusCode().value());
        assertEquals(legacyCreated + 30 * MINUTE, jdbc.queryForObject(
                "SELECT terminated_at FROM unblind_request WHERE id = 'UB-LEGACY'",
                Long.class));
    }

    // ---------------- 幂等 ----------------

    @Test
    void rejectAndCancel_idempotent_replayOriginalResponse_changedParams409() throws Exception {
        createExperimentWithParticipants("VT-7", 2);

        // 拒绝：同键同参重放原 REJECTED 快照；换原因 409
        String ub1 = jsonFromApplyBody("VT-7", "P1", "vt7-apply-1", "{\"reason\":\"r\"}");
        ResponseEntity<String> reject1 = exchange(
                "/api/unblind-requests/" + ub1 + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "vt7-reject-key"),
                "{\"reason\":\"原因甲\"}");
        assertEquals(200, reject1.getStatusCode().value());
        ResponseEntity<String> rejectReplay = exchange(
                "/api/unblind-requests/" + ub1 + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "vt7-reject-key"),
                "{\"reason\":\"原因甲\"}");
        assertEquals(200, rejectReplay.getStatusCode().value());
        assertEquals(reject1.getBody(), rejectReplay.getBody());
        assertEquals(409, exchange(
                "/api/unblind-requests/" + ub1 + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "vt7-reject-key"),
                "{\"reason\":\"原因乙\"}").getStatusCode().value());

        // 撤销：同键重放原 CANCELLED 快照；换新键再次撤销 409
        String ub2 = jsonFromApplyBody("VT-7", "P2", "vt7-apply-2", "{\"reason\":\"r\"}");
        ResponseEntity<String> cancel1 = exchange(
                "/api/unblind-requests/" + ub2 + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "vt7-cancel-key"), null);
        assertEquals(200, cancel1.getStatusCode().value());
        ResponseEntity<String> cancelReplay = exchange(
                "/api/unblind-requests/" + ub2 + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "vt7-cancel-key"), null);
        assertEquals(200, cancelReplay.getStatusCode().value());
        assertEquals(cancel1.getBody(), cancelReplay.getBody());
        assertEquals(409, exchange(
                "/api/unblind-requests/" + ub2 + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "vt7-cancel-other-key"), null)
                .getStatusCode().value());

        // 业务失败（空原因 400）不占键：同 requestId 换合法原因后成功
        String ub3 = jsonFromApplyBody("VT-7", "P1", "vt7-apply-again", "{\"reason\":\"r2\"}");
        // P1 旧申请已拒绝，可重新申请；先让新申请进入待审
        assertEquals(400, exchange(
                "/api/unblind-requests/" + ub3 + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "vt7-reject-retry-key"),
                "{\"reason\":\"\"}").getStatusCode().value());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'vt7-reject-retry-key'",
                Long.class));
        assertEquals(200, exchange(
                "/api/unblind-requests/" + ub3 + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "vt7-reject-retry-key"),
                "{\"reason\":\"补正原因\"}").getStatusCode().value());

        // 同申请键不同 validMinutes：异参 409
        String ub4 = jsonFromApplyBody("VT-7", "P2", "vt7-apply-p2-again", "{\"reason\":\"r3\"}");
        assertEquals(409, exchange(
                "/api/experiments/VT-7/participants/P2/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "vt7-apply-p2-again"),
                "{\"reason\":\"r3\",\"validMinutes\":10}").getStatusCode().value());
    }

    // ---------------- 并发 ----------------

    @Test
    void concurrentReapplyAfterExpiry_onlyOneSucceeds_oldArchivedExactlyOnce() throws Exception {
        createExperimentWithParticipants("VT-8", 1);
        long t0 = clock.nowMillis();
        String oldId = jsonFromApplyBody("VT-8", "P1", "vt8-apply-old",
                "{\"reason\":\"r\",\"validMinutes\":1}");
        clock.setTime(t0 + MINUTE + 1);

        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String reqId = "vt8-reapply-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return exchange(
                            "/api/experiments/VT-8/participants/P1/unblind-requests",
                            HttpMethod.POST, headers("coord-1", "COORDINATOR", reqId),
                            "{\"reason\":\"并发重申\"}").getStatusCode().value();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(s -> s == 201).count(),
                    "并发重申最多一个成功");
            assertEquals(threads - 1L, statuses.stream().filter(s -> s == 409).count(),
                    "其余并发重申全部 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        long allocId = allocationId("VT-8", "P1");
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ?", Long.class,
                allocId), "旧行归档 + 新行待审");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? AND status = 'PENDING'",
                Long.class, allocId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? AND status = 'EXPIRED'",
                Long.class, allocId));
        assertEquals(oldId, jdbc.queryForObject(
                "SELECT id FROM unblind_request WHERE allocation_id = ? AND status = 'EXPIRED'",
                String.class, allocId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? "
                        + "AND pending_allocation_id IS NOT NULL", Long.class, allocId),
                "同一参与者始终至多一份有效待审");
        // 失败事务不残留占位（旧申请成功键 + 一个重申成功键，失败重申不占键）
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE operation = 'unblind.apply'",
                Long.class));
    }

    @Test
    void concurrentApproveAndCancel_exactlyOneTerminal_noBlindResidueOnFailure() throws Exception {
        createExperimentWithParticipants("VT-9", 1);
        String ubId = jsonFromApplyBody("VT-9", "P1", "vt9-apply", "{\"reason\":\"r\"}");

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<Integer> approveFuture = pool.submit(() -> {
                awaitQuietly(barrier);
                return exchange("/api/unblind-requests/" + ubId + "/approval",
                        HttpMethod.POST, headers("rev-2", "REVIEWER", "vt9-approve-key"), null)
                        .getStatusCode().value();
            });
            Future<Integer> cancelFuture = pool.submit(() -> {
                awaitQuietly(barrier);
                return exchange("/api/unblind-requests/" + ubId + "/cancellation",
                        HttpMethod.POST, headers("coord-1", "COORDINATOR", "vt9-cancel-key"), null)
                        .getStatusCode().value();
            });
            Set<Integer> statuses = new HashSet<>();
            statuses.add(approveFuture.get(30, TimeUnit.SECONDS));
            statuses.add(cancelFuture.get(30, TimeUnit.SECONDS));
            assertEquals(Set.of(200, 409), statuses, "批准与撤销竞争只能一个成功");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 只能形成一个终态；撤销胜出时不得有盲底残留
        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId);
        String treatment = jdbc.queryForObject(
                "SELECT treatment FROM unblind_request WHERE id = ?", String.class, ubId);
        Long pendingPlaceholder = jdbc.queryForObject(
                "SELECT pending_allocation_id FROM unblind_request WHERE id = ?", Long.class, ubId);
        assertNull(pendingPlaceholder);
        if ("APPROVED".equals(finalStatus)) {
            assertNotNull(treatment, "批准胜出必须写入处理代码");
        } else {
            assertEquals("CANCELLED", finalStatus);
            assertNull(treatment, "撤销胜出不得残留盲底");
        }
        // 申请人结果查询与终态一致：批准可得代码，撤销 409
        int resultStatus = exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value();
        assertEquals("APPROVED".equals(finalStatus) ? 200 : 409, resultStatus);
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException | BrokenBarrierException | java.util.concurrent.TimeoutException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
