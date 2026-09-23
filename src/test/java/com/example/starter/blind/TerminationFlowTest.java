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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 揭盲申请终止机制：有效期与到期展示、撤销、拒绝、终态不可互转、
 * 到期归档后重新申请（新 ID、历史保留）、结果查询不泄露盲底、
 * 幂等回放与并发裁决（真实 H2 + 真实多线程，超时断言）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminationFlowTest extends AbstractBlindIntegrationTest {

    private static final long BASE = 1_700_000_000_000L;
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

    private String apply(String expId, String actor, String key, String body) {
        try {
            ResponseEntity<String> resp = exchange(
                    "/api/experiments/" + expId + "/participants/PA/unblind-requests",
                    HttpMethod.POST, headers(actor, "COORDINATOR", key), body);
            return resp.getStatusCode().value() == 201
                    ? json(resp).path("requestId").asText() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long allocationId(String expId) {
        return jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = '" + expId
                        + "' AND participant_id = 'PA'", Long.class);
    }

    private int countRequests(String expId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE experiment_id = ?",
                Integer.class, expId);
    }

    // ---------------- 有效期 ----------------

    @Test
    void apply_defaultValidMinutes30_andCustomValidityRecordedAsExpiresAt() throws Exception {
        setupExperimentWithParticipant("T-1", "t1");

        ResponseEntity<String> byDefault = exchange(
                "/api/experiments/T-1/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t1-apply-default"),
                "{\"reason\":\"默认30分钟\"}");
        assertEquals(201, byDefault.getStatusCode().value());
        JsonNode body = json(byDefault);
        assertEquals(30, body.path("validMinutes").asInt());
        assertEquals(BASE + 30 * MINUTE, body.path("expiresAt").asLong());
        assertEquals(BASE, body.path("createdAt").asLong());
        assertFalse(body.path("terminatedAt").isContainerNode());
        assertTrue(body.path("terminatedAt").isNull());
        assertEquals("PENDING", body.path("status").asText());

        // 自定义 1 分钟
        jdbc.update("DELETE FROM unblind_request WHERE experiment_id = 'T-1'");
        jdbc.update("DELETE FROM idempotent_request");
        ResponseEntity<String> custom = exchange(
                "/api/experiments/T-1/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t1-apply-1m"),
                "{\"reason\":\"1分钟有效\",\"validMinutes\":1}");
        assertEquals(201, custom.getStatusCode().value());
        JsonNode customBody = json(custom);
        assertEquals(1, customBody.path("validMinutes").asInt());
        assertEquals(BASE + MINUTE, customBody.path("expiresAt").asLong());

        // 边界 60 合法；0 与 61 非法
        jdbc.update("DELETE FROM unblind_request WHERE experiment_id = 'T-1'");
        jdbc.update("DELETE FROM idempotent_request");
        assertEquals(201, exchange(
                "/api/experiments/T-1/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t1-apply-60"),
                "{\"reason\":\"60分钟\",\"validMinutes\":60}").getStatusCode().value());
        jdbc.update("DELETE FROM unblind_request WHERE experiment_id = 'T-1'");
        jdbc.update("DELETE FROM idempotent_request");
        assertEquals(400, exchange(
                "/api/experiments/T-1/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t1-apply-0"),
                "{\"reason\":\"0分钟\",\"validMinutes\":0}").getStatusCode().value());
        assertEquals(400, exchange(
                "/api/experiments/T-1/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t1-apply-61"),
                "{\"reason\":\"61分钟\",\"validMinutes\":61}").getStatusCode().value());
        // 失败不占键：换成合法参数同键成功
        assertEquals(201, exchange(
                "/api/experiments/T-1/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t1-apply-0"),
                "{\"reason\":\"合法重试\",\"validMinutes\":5}").getStatusCode().value());
    }

    // ---------------- 到期：查询展示不写库，裁决全部 409 ----------------

    @Test
    void expiredRequest_showsExpiredByClock_withoutWriting_andAllDecisionsReturn409() throws Exception {
        setupExperimentWithParticipant("T-2", "t2");
        String ubId = apply("T-2", "coord-1", "t2-apply", "{\"reason\":\"到期测试\"}");
        long expectedExpiresAt = BASE + 30 * MINUTE;

        // 到期前一刻仍为 PENDING
        clock.setTime(expectedExpiresAt - 1);
        assertEquals("PENDING", json(exchange("/api/unblind-requests/" + ubId,
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null))
                .path("status").asText());

        // 到达 expiresAt：普通查询展示 EXPIRED
        clock.setTime(expectedExpiresAt);
        JsonNode expiredView = json(exchange("/api/unblind-requests/" + ubId,
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null));
        assertEquals("EXPIRED", expiredView.path("status").asText());
        assertEquals(expectedExpiresAt, expiredView.path("expiresAt").asLong());
        assertEquals(expectedExpiresAt, expiredView.path("terminatedAt").asLong(),
                "到期终止时间固定为 expiresAt");
        assertTrue(expiredView.path("terminateActor").isNull(),
                "到期不得伪造人工处理人");
        assertTrue(expiredView.path("terminateReason").isNull());
        assertFalse(expiredView.has("treatment"));

        // 普通查询不写库：行仍为 PENDING，treatment 为空，占位仍在
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND treatment IS NULL "
                        + "AND pending_allocation_id = ?", Integer.class, ubId, allocationId("T-2")));

        // 批准/拒绝/撤销到期申请全部 409，且不残留盲底或终态
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t2-approve-late"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t2-reject-late"),
                "{\"reason\":\"太迟了\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t2-cancel-late"),
                "{\"reason\":\"太迟了\"}").getStatusCode().value());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND treatment IS NULL "
                        + "AND reviewer_actor IS NULL", Integer.class, ubId));

        // 到期后结果查询：申请人 409，非申请人 403，均无处理代码
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("rev-2", "REVIEWER", null), null)
                .getStatusCode().value());
    }

    // ---------------- 拒绝 ----------------

    @Test
    void reject_byAnotherReviewerWithReason_succeeds_andBranches() throws Exception {
        setupExperimentWithParticipant("T-3", "t3");
        String ubId = apply("T-3", "coord-1", "t3-apply", "{\"reason\":\"请拒绝我\"}");

        // REVIEWER 角色但与申请人同 actorId：自拒 403
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("coord-1", "REVIEWER", "t3-self-reject"),
                "{\"reason\":\"我拒绝我自己\"}").getStatusCode().value());
        // COORDINATOR 不能拒绝
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("coord-2", "COORDINATOR", "t3-coord-reject"),
                "{\"reason\":\"协调员拒绝\"}").getStatusCode().value());
        // 非空原因缺失：400
        assertEquals(400, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t3-blank-reject"),
                "{\"reason\":\"   \"}").getStatusCode().value());
        // 失败不占键
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 't3-blank-reject'",
                Integer.class));

        clock.advance(2 * MINUTE);
        long rejectAt = BASE + 2 * MINUTE;
        ResponseEntity<String> rejected = exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "t3-reject"),
                "{\"reason\":\"材料不完整\"}");
        assertEquals(200, rejected.getStatusCode().value());
        JsonNode body = json(rejected);
        assertEquals("REJECTED", body.path("status").asText());
        assertEquals("rev-2", body.path("reviewerActor").asText());
        assertEquals("材料不完整", body.path("terminateReason").asText());
        assertEquals("rev-2", body.path("terminateActor").asText());
        assertEquals(rejectAt, body.path("terminatedAt").asLong());
        assertEquals(rejectAt, body.path("reviewedAt").asLong());
        assertFalse(body.has("treatment"));

        // 终态不可互转：再批准/再拒绝/再撤销全部 409
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-3", "REVIEWER", "t3-approve-after"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-3", "REVIEWER", "t3-reject-after"),
                "{\"reason\":\"再拒\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t3-cancel-after"), null)
                .getStatusCode().value());

        // 库内未写盲底、占位已释放
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND status = 'REJECTED' "
                        + "AND treatment IS NULL AND pending_allocation_id IS NULL",
                Integer.class, ubId));

        // 结果查询：申请人 409，拒绝人/其他人 403
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("rev-2", "REVIEWER", null), null)
                .getStatusCode().value());

        // 拒绝人可查申请状态；其他 REVIEWER 403
        assertEquals(200, exchange("/api/unblind-requests/" + ubId,
                HttpMethod.GET, headers("rev-2", "REVIEWER", null), null)
                .getStatusCode().value());
        assertEquals(403, exchange("/api/unblind-requests/" + ubId,
                HttpMethod.GET, headers("rev-3", "REVIEWER", null), null)
                .getStatusCode().value());
    }

    // ---------------- 撤销 ----------------

    @Test
    void cancel_byApplicantCoordinator_succeeds_andBranches() throws Exception {
        setupExperimentWithParticipant("T-4", "t4");
        String ubId = apply("T-4", "coord-1", "t4-apply", "{\"reason\":\"请允许撤销\"}");

        // 另一名协调员不能撤销他人申请
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-2", "COORDINATOR", "t4-other-cancel"),
                "{\"reason\":\"代撤销\"}").getStatusCode().value());
        // REVIEWER 不能撤销
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t4-reviewer-cancel"),
                "{\"reason\":\"审阅员撤销\"}").getStatusCode().value());

        // 申请人本人撤销（带原因）
        clock.advance(MINUTE);
        long cancelAt = BASE + MINUTE;
        ResponseEntity<String> cancelled = exchange(
                "/api/unblind-requests/" + ubId + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t4-cancel"),
                "{\"reason\":\"申请人主动撤回\"}");
        assertEquals(200, cancelled.getStatusCode().value());
        JsonNode body = json(cancelled);
        assertEquals("CANCELLED", body.path("status").asText());
        assertEquals("申请人主动撤回", body.path("terminateReason").asText());
        assertEquals("coord-1", body.path("terminateActor").asText());
        assertEquals(cancelAt, body.path("terminatedAt").asLong());
        assertTrue(body.path("reviewerActor").isNull());
        assertFalse(body.has("treatment"));

        // 终态不可互转
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t4-approve-after"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t4-reject-after"),
                "{\"reason\":\"再拒\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t4-cancel-again"), null)
                .getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND status = 'CANCELLED' "
                        + "AND pending_allocation_id IS NULL AND treatment IS NULL",
                Integer.class, ubId));

        // 申请人结果查询 409
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
    }

    @Test
    void cancel_withoutBodyOrReason_succeeds() {
        setupExperimentWithParticipant("T-4B", "t4b");
        String ubId = apply("T-4B", "coord-1", "t4b-apply", "{\"reason\":\"无体撤销\"}");

        // 完全不带请求体也可以撤销
        ResponseEntity<String> cancelled = exchange(
                "/api/unblind-requests/" + ubId + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t4b-cancel"), null);
        assertEquals(200, cancelled.getStatusCode().value());
        String terminateReason = jdbc.queryForObject(
                "SELECT terminate_reason FROM unblind_request WHERE id = ?",
                String.class, ubId);
        assertEquals(null, terminateReason);
    }

    // ---------------- 重新申请：归档/新 ID/历史保留/重放旧快照 ----------------

    @Test
    void reapplyAfterExpiry_archivesOldWithNewId_preservesHistory() throws Exception {
        setupExperimentWithParticipant("T-5", "t5");
        String firstId = apply("T-5", "coord-1", "t5-apply-1",
                "{\"reason\":\"第一次\",\"validMinutes\":1}");
        assertNotNull(firstId);
        long firstExpiresAt = BASE + MINUTE;

        // 未到期不能重新申请
        clock.setTime(firstExpiresAt - 1);
        assertEquals(409, exchange(
                "/api/experiments/T-5/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t5-apply-too-early"),
                "{\"reason\":\"太早了\"}").getStatusCode().value());

        // 到期后重新申请：同一事务归档旧占位并创建新 PENDING
        clock.setTime(firstExpiresAt + 30_000L);
        ResponseEntity<String> second = exchange(
                "/api/experiments/T-5/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t5-apply-2"),
                "{\"reason\":\"第二次\",\"validMinutes\":10}");
        assertEquals(201, second.getStatusCode().value());
        String secondId = json(second).path("requestId").asText();
        assertFalse(firstId.equals(secondId), "重新申请不得复用旧 ID");
        assertEquals(2, countRequests("T-5"), "旧申请历史必须保留");

        // 旧申请：库内已归档 EXPIRED，终止时间固定为其 expiresAt，无处理人、无盲底
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, firstId));
        assertEquals(firstExpiresAt, jdbc.queryForObject(
                "SELECT terminated_at FROM unblind_request WHERE id = ?",
                Long.class, firstId));
        assertEquals(null, jdbc.queryForObject(
                "SELECT terminate_actor FROM unblind_request WHERE id = ?",
                String.class, firstId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND treatment IS NULL "
                        + "AND pending_allocation_id IS NULL", Integer.class, firstId));

        // 新申请未到期：有效待审唯一
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? "
                        + "AND pending_allocation_id = ?", Integer.class,
                allocationId("T-5"), allocationId("T-5")));
        JsonNode secondView = json(exchange("/api/unblind-requests/" + secondId,
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null));
        assertEquals("PENDING", secondView.path("status").asText());
        assertEquals(10, secondView.path("validMinutes").asInt());
        assertEquals(firstExpiresAt + 30_000L + 10 * MINUTE,
                secondView.path("expiresAt").asLong());

        // 旧申请查询视图仍为 EXPIRED，历史不可覆盖
        JsonNode firstView = json(exchange("/api/unblind-requests/" + firstId,
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null));
        assertEquals("EXPIRED", firstView.path("status").asText());
        assertEquals("第一次", firstView.path("reason").asText());

        // 重放旧申请键：只返回原创建快照（旧 ID、旧 expiresAt），不重占位置、不新增行
        ResponseEntity<String> replayOld = exchange(
                "/api/experiments/T-5/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t5-apply-1"),
                "{\"reason\":\"第一次\",\"validMinutes\":1}");
        assertEquals(201, replayOld.getStatusCode().value());
        assertEquals(firstId, json(replayOld).path("requestId").asText());
        assertEquals(firstExpiresAt, json(replayOld).path("expiresAt").asLong());
        assertEquals(2, countRequests("T-5"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? "
                        + "AND status = 'PENDING'", Integer.class, allocationId("T-5")));
    }

    @Test
    void reapplyAfterCancelOrReject_createsNewRequestAndKeepsHistory() {
        setupExperimentWithParticipant("T-6", "t6");
        String cancelledId = apply("T-6", "coord-1", "t6-apply-c", "{\"reason\":\"将撤销\"}");
        assertEquals(200, exchange("/api/unblind-requests/" + cancelledId + "/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t6-cancel"),
                "{\"reason\":\"撤了\"}").getStatusCode().value());
        String secondId = apply("T-6", "coord-1", "t6-apply-2", "{\"reason\":\"撤销后重申\"}");
        assertNotNull(secondId);
        assertFalse(cancelledId.equals(secondId));
        assertEquals(2, countRequests("T-6"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? "
                        + "AND pending_allocation_id = ?", Integer.class,
                allocationId("T-6"), allocationId("T-6")));
    }

    // ---------------- 幂等：拒绝/撤销重放与异参冲突 ----------------

    @Test
    void rejectAndCancel_idempotentReplay_andDifferentParamsConflict() throws Exception {
        setupExperimentWithParticipant("T-7", "t7");
        String ubId = apply("T-7", "coord-1", "t7-apply", "{\"reason\":\"幂等裁决\"}");

        // 拒绝：同键同体重放原 200，不产生第二次终态写入
        ResponseEntity<String> reject1 = exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "t7-reject-key"),
                "{\"reason\":\"同因拒绝\"}");
        assertEquals(200, reject1.getStatusCode().value());
        ResponseEntity<String> rejectReplay = exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "t7-reject-key"),
                "{\"reason\":\"同因拒绝\"}");
        assertEquals(200, rejectReplay.getStatusCode().value());
        assertEquals(reject1.getBody(), rejectReplay.getBody());
        // 同键改原因：409
        assertEquals(409, exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "t7-reject-key"),
                "{\"reason\":\"不同原因\"}").getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND status = 'REJECTED'",
                Integer.class, ubId));

        // 另一份申请：撤销同键异参（携带/不携带原因）409
        String ubId2 = apply("T-7", "coord-2", "t7-apply-2", "{\"reason\":\"第二份\"}");
        ResponseEntity<String> cancel1 = exchange(
                "/api/unblind-requests/" + ubId2 + "/cancellation", HttpMethod.POST,
                headers("coord-2", "COORDINATOR", "t7-cancel-key"),
                "{\"reason\":\"带原因\"}");
        assertEquals(200, cancel1.getStatusCode().value());
        assertEquals(409, exchange(
                "/api/unblind-requests/" + ubId2 + "/cancellation", HttpMethod.POST,
                headers("coord-2", "COORDINATOR", "t7-cancel-key"),
                "{\"reason\":\"改了原因\"}").getStatusCode().value());
        ResponseEntity<String> cancelReplay = exchange(
                "/api/unblind-requests/" + ubId2 + "/cancellation", HttpMethod.POST,
                headers("coord-2", "COORDINATOR", "t7-cancel-key"),
                "{\"reason\":\"带原因\"}");
        assertEquals(200, cancelReplay.getStatusCode().value());
        assertEquals(cancel1.getBody(), cancelReplay.getBody());
    }

    @Test
    void roleCheckPrecedesIdempotency_forTerminationEndpoints() {
        setupExperimentWithParticipant("T-8", "t8");
        String ubId = apply("T-8", "coord-1", "t8-apply", "{\"reason\":\"权限优先\"}");

        // REVIEWER 先用键成功拒绝
        assertEquals(200, exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "t8-reject-key"),
                "{\"reason\":\"合法拒绝\"}").getStatusCode().value());
        // COORDINATOR 用同键：角色校验先于幂等，直接 403
        assertEquals(403, exchange(
                "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t8-reject-key"),
                "{\"reason\":\"合法拒绝\"}").getStatusCode().value());

        // 撤销端点：REVIEWER 用协调员的键 403（权限先于幂等）
        String ubId2 = apply("T-8", "coord-1", "t8-apply-2", "{\"reason\":\"第二份\"}");
        assertEquals(403, exchange(
                "/api/unblind-requests/" + ubId2 + "/cancellation", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "t8-cancel-key"), null).getStatusCode().value());
        assertEquals(200, exchange(
                "/api/unblind-requests/" + ubId2 + "/cancellation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t8-cancel-key"), null)
                .getStatusCode().value());
    }

    // ---------------- 404 / 400 边界 ----------------

    @Test
    void terminationEndpoints_unknownRequest_404() {
        assertEquals(404, exchange("/api/unblind-requests/UB-NOPE/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t9-approve-missing"), null)
                .getStatusCode().value());
        assertEquals(404, exchange("/api/unblind-requests/UB-NOPE/rejection",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t9-reject-missing"),
                "{\"reason\":\"x\"}").getStatusCode().value());
        assertEquals(404, exchange("/api/unblind-requests/UB-NOPE/cancellation",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t9-cancel-missing"), null)
                .getStatusCode().value());
    }

    // ---------------- 并发：裁决竞争与新申请竞争 ----------------

    @Test
    void concurrentApproveAndReject_exactlyOneTerminalState() throws Exception {
        setupExperimentWithParticipant("T-10", "t10");
        String ubId = apply("T-10", "coord-1", "t10-apply", "{\"reason\":\"并发裁决\"}");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    if (idx % 2 == 0) {
                        return exchange("/api/unblind-requests/" + ubId + "/approval",
                                HttpMethod.POST,
                                headers("rev-" + (10 + idx), "REVIEWER", "t10-approve-" + idx),
                                null).getStatusCode().value();
                    }
                    return exchange("/api/unblind-requests/" + ubId + "/rejection",
                            HttpMethod.POST,
                            headers("rev-" + (10 + idx), "REVIEWER", "t10-reject-" + idx),
                            "{\"reason\":\"竞争拒绝" + idx + "\"}").getStatusCode().value();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 200).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, success, "只能形成一个终态");
            assertEquals(threads - 1L, conflict, "其余并发裁决全部 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 库内恰好一个终态，无多处理人/盲底矛盾
        List<String> statusesInDb = jdbc.queryForList(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId);
        assertEquals(List.of("APPROVED").equals(statusesInDb)
                        || List.of("REJECTED").equals(statusesInDb),
                true, "终态必须是 APPROVED 或 REJECTED 之一: " + statusesInDb);
        long terminalRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? "
                        + "AND pending_allocation_id IS NULL", Integer.class, ubId);
        assertEquals(1, terminalRows);
        // 批准与拒绝字段不得同时出现
        long contradictory = jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? "
                        + "AND treatment IS NOT NULL AND terminate_reason IS NOT NULL",
                Integer.class, ubId);
        assertEquals(0, contradictory, "失败方不得残留盲底或拒绝原因");
    }

    @Test
    void concurrentNewApplications_afterExpiry_atMostOneSucceeds() throws Exception {
        setupExperimentWithParticipant("T-11", "t11");
        String oldId = apply("T-11", "coord-1", "t11-apply-old",
                "{\"reason\":\"旧申请\",\"validMinutes\":1}");
        assertNotNull(oldId);
        // 全部线程在到期之后发起新申请
        clock.setTime(BASE + MINUTE + 10_000L);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> exchange(
                        "/api/experiments/T-11/participants/PA/unblind-requests",
                        HttpMethod.POST,
                        headers("coord-1", "COORDINATOR", "t11-apply-new-" + idx),
                        "{\"reason\":\"并发重申" + idx + "\"}").getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, success, "并发新申请最多一个成功");
            assertEquals(threads - 1L, conflict, "其余全部 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 旧申请已归档 EXPIRED；恰好一个新的有效待审；失败方不残留行
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, oldId));
        assertEquals(2, countRequests("T-11"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? "
                        + "AND status = 'PENDING' AND pending_allocation_id = ?",
                Integer.class, allocationId("T-11"), allocationId("T-11")));
        List<String> newIds = jdbc.queryForList(
                "SELECT id FROM unblind_request WHERE allocation_id = ? AND id <> ?",
                String.class, allocationId("T-11"), oldId);
        assertEquals(1, newIds.size());
        // 成功的新申请可被批准（终态竞争后系统仍一致）
        assertEquals(200, exchange("/api/unblind-requests/" + newIds.get(0) + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t11-approve-new"), null)
                .getStatusCode().value());
    }

    @Test
    void concurrentNewApplications_whilePending_allButOneConflict() throws Exception {
        setupExperimentWithParticipant("T-12", "t12");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> exchange(
                        "/api/experiments/T-12/participants/PA/unblind-requests",
                        HttpMethod.POST,
                        headers("coord-1", "COORDINATOR", "t12-apply-" + idx),
                        "{\"reason\":\"并发占位" + idx + "\"}").getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(s -> s == 201).count(),
                    "同一参与者始终至多一份有效待审");
            assertEquals(threads - 1L, statuses.stream().filter(s -> s == 409).count());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, countRequests("T-12"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? "
                        + "AND pending_allocation_id = ?", Integer.class,
                allocationId("T-12"), allocationId("T-12")));
    }

    // ---------------- 关闭/退组不影响裁决与终态 ----------------

    @Test
    void approvedAfterCloseAndWithdrawal_stillAccessible_butExpiryStillBlocksDecision()
            throws Exception {
        setupExperimentWithParticipant("T-13", "t13");
        String ubId = apply("T-13", "coord-1", "t13-apply",
                "{\"reason\":\"长期有效\",\"validMinutes\":60}");

        // 退组 + 关闭实验
        assertEquals(200, exchange("/api/experiments/T-13/participants/PA/withdrawal",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "t13-withdraw"), null)
                .getStatusCode().value());
        assertEquals(200, exchange("/api/experiments/T-13/close", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "t13-close"), null).getStatusCode().value());

        // 未到期仍可被另一名 REVIEWER 批准，关闭/退组不撤销
        clock.advance(5 * MINUTE);
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t13-approve"), null)
                .getStatusCode().value());
        ResponseEntity<String> resultResp = exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, resultResp.getStatusCode().value());
        JsonNode result = json(resultResp);
        assertEquals("APPROVED", result.path("status").asText());
        assertTrue(Set.of("A", "B").contains(result.path("treatment").asText()));

        // 另一份新申请在关闭实验下仍可提出（申请不依赖实验 OPEN），但到期后照样不能裁决
        String ubId2 = apply("T-13", "coord-1", "t13-apply-2",
                "{\"reason\":\"短有效期\",\"validMinutes\":1}");
        assertNotNull(ubId2);
        clock.setTime(BASE + 5 * MINUTE + MINUTE + 1);
        assertEquals(409, exchange("/api/unblind-requests/" + ubId2 + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "t13-approve-2-late"), null)
                .getStatusCode().value());
    }

    @Test
    void expiredView_serializesExpectedFields_only() throws Exception {
        setupExperimentWithParticipant("T-14", "t14");
        String ubId = apply("T-14", "coord-1", "t14-apply",
                "{\"reason\":\"字段检查\",\"validMinutes\":1}");
        clock.setTime(BASE + MINUTE);
        JsonNode view = json(exchange("/api/unblind-requests/" + ubId,
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null));
        Set<String> fields = new HashSet<>();
        view.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("requestId", "experimentId", "participantId", "reason",
                "applicantActor", "reviewerActor", "status", "createdAt", "reviewedAt",
                "expiresAt", "validMinutes", "terminatedAt", "terminateReason",
                "terminateActor"), fields);
        assertFalse(fields.contains("treatment"), "申请视图任何状态下都不得含处理代码");
    }
}
