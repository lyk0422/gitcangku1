package com.example.starter.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.testsupport.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 写操作 requestId 幂等：同键同参回放、异参/异人 409、失败不占键、鉴权先于回放。 */
class IdempotencyApiTest extends AbstractIntegrationTest {

    private static final String COORDINATOR = "coord-1";
    private static final String COORDINATOR_2 = "coord-2";
    private static final String REVIEWER = "rev-1";

    @Test
    void sameRequestIdWithSameParamsReplaysOriginalResult() throws Exception {
        createExperiment("EXP-IDEM", 2, "req-idem-create");
        Map<String, String> body = Map.of("participantId", "P-1", "requestId", "shared-request-id");

        MvcResult first = postJson("/api/experiments/EXP-IDEM/enroll", COORDINATOR, "COORDINATOR", body);
        MvcResult second = postJson("/api/experiments/EXP-IDEM/enroll", COORDINATOR, "COORDINATOR", body);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXP-IDEM'", Integer.class);
        assertThat(count).isEqualTo(1);
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'shared-request-id'", Integer.class);
        assertThat(records).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsIsConflict() throws Exception {
        createExperiment("EXP-CONF", 2, "req-conf-create");
        MvcResult first = postJson("/api/experiments/EXP-CONF/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "reused-id"));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        MvcResult differentParams = postJson("/api/experiments/EXP-CONF/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-2", "requestId", "reused-id"));
        assertThat(differentParams.getResponse().getStatus()).isEqualTo(409);

        // 原结果不受影响，P-2 未被分配
        Integer p2 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXP-CONF' AND participant_id = 'P-2'",
                Integer.class);
        assertThat(p2).isZero();
    }

    @Test
    void sameRequestIdByDifferentActorIsConflict() throws Exception {
        createExperiment("EXP-ACTOR", 2, "req-actor-create");
        assertThat(postJson("/api/experiments/EXP-ACTOR/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "actor-id")).getResponse().getStatus())
                .isEqualTo(201);

        MvcResult otherActor = postJson("/api/experiments/EXP-ACTOR/enroll", COORDINATOR_2, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "actor-id"));
        assertThat(otherActor.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void sameRequestIdWithDifferentRoleIsForbiddenBeforeReplay() throws Exception {
        createExperiment("EXP-ROLE-IDEM", 2, "req-ri-create");
        assertThat(postJson("/api/experiments/EXP-ROLE-IDEM/close", COORDINATOR, "COORDINATOR",
                Map.of("requestId", "role-id")).getResponse().getStatus()).isEqualTo(200);
        // close 仅 COORDINATOR 可用：权限校验先于幂等回放，换角色直接 403 而不是回放结果
        MvcResult differentRole = postJson("/api/experiments/EXP-ROLE-IDEM/close", REVIEWER, "REVIEWER",
                Map.of("requestId", "role-id"));
        assertThat(differentRole.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void sameRoleButDifferentActorReachingIdempotencyLayerIsConflict() throws Exception {
        // approve 是 REVIEWER 专属：两名 REVIEWER 用同一 requestId，越过权限层后由幂等层判 409
        createExperiment("EXP-ROLE2", 2, "req-role2-create");
        postJson("/api/experiments/EXP-ROLE2/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-role2-enroll"));
        MvcResult requested = postJson("/api/experiments/EXP-ROLE2/unblind-requests",
                COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "reason", "audit", "requestId", "req-role2-ureq"));
        long unblindRequestId = readBody(requested).get("unblindRequestId").asLong();

        assertThat(postJson(
                "/api/experiments/EXP-ROLE2/unblind-requests/" + unblindRequestId + "/approve",
                REVIEWER, "REVIEWER", Map.of("requestId", "shared-approve-role"))
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult otherReviewer = postJson(
                "/api/experiments/EXP-ROLE2/unblind-requests/" + unblindRequestId + "/approve",
                "rev-2", "REVIEWER", Map.of("requestId", "shared-approve-role"));
        assertThat(otherReviewer.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedBusinessCallDoesNotOccupyRequestId() throws Exception {
        createExperiment("EXP-FAIL", 2, "req-fail-create");
        // 对不存在的实验登记，业务失败
        MvcResult failed = postJson("/api/experiments/EXP-GHOST/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "fail-then-succeed"));
        assertThat(failed.getResponse().getStatus()).isEqualTo(404);

        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'fail-then-succeed'", Integer.class);
        assertThat(records).isZero();

        // 同 requestId 用于一个合法请求，应当成功
        MvcResult success = postJson("/api/experiments/EXP-FAIL/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "fail-then-succeed"));
        assertThat(success.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void replayOfUnblindApprovalReturnsOriginalApprover() throws Exception {
        createExperiment("EXP-UB-IDEM", 2, "req-ubi-create");
        postJson("/api/experiments/EXP-UB-IDEM/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-ubi-enroll"));
        MvcResult requested = postJson("/api/experiments/EXP-UB-IDEM/unblind-requests",
                COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "reason", "audit", "requestId", "req-ubi-ureq"));
        long unblindRequestId = readBody(requested).get("unblindRequestId").asLong();

        Map<String, String> approveBody = Map.of("requestId", "approve-replay-id");
        MvcResult first = postJson(
                "/api/experiments/EXP-UB-IDEM/unblind-requests/" + unblindRequestId + "/approve",
                REVIEWER, "REVIEWER", approveBody);
        MvcResult second = postJson(
                "/api/experiments/EXP-UB-IDEM/unblind-requests/" + unblindRequestId + "/approve",
                REVIEWER, "REVIEWER", approveBody);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        Integer approvals = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ?", Integer.class, unblindRequestId);
        assertThat(approvals).isEqualTo(1);
    }

    @Test
    void permissionCheckHappensBeforeIdempotentReplay() throws Exception {
        createExperiment("EXP-AUTH", 2, "req-auth-create");
        assertThat(postJson("/api/experiments/EXP-AUTH/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "auth-id")).getResponse().getStatus())
                .isEqualTo(201);

        // REVIEWER 无权登记：必须返回 403，即使该 requestId 已有成功记录（不回放）
        MvcResult forbidden = postJson("/api/experiments/EXP-AUTH/enroll", REVIEWER, "REVIEWER",
                Map.of("participantId", "P-1", "requestId", "auth-id"));
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void missingOrMalformedActorHeadersAreRejectedBeforeBusinessLogic() throws Exception {
        MvcResult noHeaders = postJsonNoHeaders("/api/experiments",
                Map.of("experimentId", "EXP-HEAD", "blockCount", 2, "requestId", "req-head"));
        assertThat(noHeaders.getResponse().getStatus()).isEqualTo(401);

        MvcResult badRole = postJson("/api/experiments/EXP-HEAD2/enroll", COORDINATOR, "ADMIN",
                Map.of("participantId", "P-1", "requestId", "req-head2"));
        assertThat(badRole.getResponse().getStatus()).isEqualTo(400);
    }

    private void createExperiment(String experimentId, int blockCount, String requestId) throws Exception {
        MvcResult result = postJson("/api/experiments", COORDINATOR, "COORDINATOR",
                Map.of("experimentId", experimentId, "blockCount", blockCount, "requestId", requestId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }
}
