package com.example.starter.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.testsupport.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 揭盲申请/批准/结果受控揭盲主流程与失败分支。 */
class UnblindWorkflowApiTest extends AbstractIntegrationTest {

    private static final String COORDINATOR = "coord-1";
    private static final String REVIEWER_A = "rev-1";
    private static final String REVIEWER_B = "rev-2";

    @Test
    void coordinatorRequestsReviewerApprovesAndOnlyApplicantSeesTreatment() throws Exception {
        setupEnrolledParticipant("EXP-UB", "P-1");

        MvcResult requestResult = postJson(
                "/api/experiments/EXP-UB/unblind-requests", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "reason", "suspected adverse event", "requestId", "req-ub-1"));
        assertThat(requestResult.getResponse().getStatus()).isEqualTo(201);
        JsonNode requestBody = readBody(requestResult);
        long unblindRequestId = requestBody.get("unblindRequestId").asLong();
        assertThat(requestBody.get("status").asText()).isEqualTo("PENDING");
        assertThat(requestBody.get("applicantId").asText()).isEqualTo(COORDINATOR);
        assertThat(requestBody.has("treatmentCode")).isFalse();

        // 未批准时申请人查询结果 -> 409
        assertThat(getJson(resultPath("EXP-UB", unblindRequestId), COORDINATOR, "COORDINATOR")
                .getResponse().getStatus()).isEqualTo(409);

        // 另一名 REVIEWER 批准
        MvcResult approve = postJson(
                approvePath("EXP-UB", unblindRequestId), REVIEWER_A, "REVIEWER",
                Map.of("requestId", "req-ub-approve-1"));
        assertThat(approve.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(approve).get("status").asText()).isEqualTo("APPROVED");
        assertThat(readBody(approve).get("approverId").asText()).isEqualTo(REVIEWER_A);

        // 申请人查询揭盲结果成功，处理代码与数据库映射一致
        MvcResult result = getJson(resultPath("EXP-UB", unblindRequestId), COORDINATOR, "COORDINATOR");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode resultBody = readBody(result);
        assertThat(resultBody.get("participantId").asText()).isEqualTo("P-1");
        assertThat(resultBody.get("treatmentCode").asText()).isIn("A", "B");
        assertThat(resultBody.get("blindCode").asText()).matches("[0-9a-f]{24}");
        assertThat(resultBody.get("status").asText()).isEqualTo("APPROVED");

        String dbTreatment = jdbcTemplate.queryForObject(
                "SELECT s.treatment_code FROM allocation a "
                        + "JOIN experiment_seat s ON s.experiment_id = a.experiment_id "
                        + "AND s.block_no = a.block_no AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'EXP-UB' AND a.participant_id = 'P-1'",
                String.class);
        assertThat(resultBody.get("treatmentCode").asText()).isEqualTo(dbTreatment);
    }

    @Test
    void otherActorsCannotViewUnblindResultEvenAfterApproval() throws Exception {
        long unblindRequestId = approvedRequest("EXP-FORBID", "P-9", "req-forbid");

        assertThat(getJson(resultPath("EXP-FORBID", unblindRequestId), REVIEWER_A, "REVIEWER")
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(getJson(resultPath("EXP-FORBID", unblindRequestId), "coord-other", "COORDINATOR")
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void applicantCannotApproveOwnRequest() throws Exception {
        setupEnrolledParticipant("EXP-SELF", "P-1");
        long unblindRequestId = createRequest("EXP-SELF", "P-1", "req-self-ub");
        MvcResult selfApprove = postJson(
                approvePath("EXP-SELF", unblindRequestId), COORDINATOR, "COORDINATOR",
                Map.of("requestId", "req-self-approve"));
        assertThat(selfApprove.getResponse().getStatus()).isEqualTo(403);

        // 申请仍为待审
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, unblindRequestId);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void coordinatorRoleCannotApproveAndReviewerRoleCannotRequest() throws Exception {
        setupEnrolledParticipant("EXP-ROLE", "P-1");
        // REVIEWER 不能提揭盲申请
        assertThat(postJson("/api/experiments/EXP-ROLE/unblind-requests", REVIEWER_A, "REVIEWER",
                Map.of("participantId", "P-1", "reason", "x", "requestId", "req-role-1"))
                .getResponse().getStatus()).isEqualTo(403);

        long unblindRequestId = createRequest("EXP-ROLE", "P-1", "req-role-2");
        // COORDINATOR 不能批准
        assertThat(postJson(approvePath("EXP-ROLE", unblindRequestId), COORDINATOR, "COORDINATOR",
                Map.of("requestId", "req-role-3"))
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void atMostOnePendingRequestPerAllocation() throws Exception {
        setupEnrolledParticipant("EXP-ONE", "P-1");
        assertThat(createRequestRaw("EXP-ONE", "P-1", "reason one", "req-one-1")
                .getResponse().getStatus()).isEqualTo(201);
        MvcResult second = postJson("/api/experiments/EXP-ONE/unblind-requests", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "reason", "reason two", "requestId", "req-one-2"));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE experiment_id = 'EXP-ONE'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void approvingTwiceIsConflict() throws Exception {
        long unblindRequestId = approvedRequest("EXP-TWICE", "P-1", "req-twice");
        MvcResult second = postJson(approvePath("EXP-TWICE", unblindRequestId), REVIEWER_B, "REVIEWER",
                Map.of("requestId", "req-twice-approve-2"));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        String approver = jdbcTemplate.queryForObject(
                "SELECT approver_id FROM unblind_request WHERE id = ?", String.class, unblindRequestId);
        assertThat(approver).isEqualTo(REVIEWER_A);
    }

    @Test
    void unblindRequestRequiresEnrolledAllocation() throws Exception {
        createExperiment("EXP-NONEXIST-UB", 2, "req-ne-create");
        MvcResult result = postJson(
                "/api/experiments/EXP-NONEXIST-UB/unblind-requests", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "GHOST", "reason", "no allocation", "requestId", "req-ne-ub"));
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void approvedUnblindSurvivesWithdrawalAndExperimentClose() throws Exception {
        long unblindRequestId = approvedRequest("EXP-SURVIVE", "P-1", "req-survive");

        assertThat(postJson("/api/experiments/EXP-SURVIVE/withdraw", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-survive-withdraw"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(postJson("/api/experiments/EXP-SURVIVE/close", COORDINATOR, "COORDINATOR",
                Map.of("requestId", "req-survive-close"))
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult result = getJson(resultPath("EXP-SURVIVE", unblindRequestId), COORDINATOR, "COORDINATOR");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(result).get("treatmentCode").asText()).isIn("A", "B");
    }

    @Test
    void unknownUnblindRequestReturns404AndCrossExperimentRequestReturns404() throws Exception {
        setupEnrolledParticipant("EXP-CROSS", "P-1");
        long unblindRequestId = approvedRequest("EXP-CROSS", "P-1", "req-cross");
        assertThat(getJson(resultPath("OTHER", unblindRequestId), COORDINATOR, "COORDINATOR")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(getJson(resultPath("EXP-CROSS", 999999L), COORDINATOR, "COORDINATOR")
                .getResponse().getStatus()).isEqualTo(404);
    }

    private void setupEnrolledParticipant(String experimentId, String participantId) throws Exception {
        String suffix = experimentId.toLowerCase();
        createExperiment(experimentId, 2, "req-" + suffix + "-create");
        assertThat(postJson("/api/experiments/" + experimentId + "/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", participantId, "requestId", "req-" + suffix + "-enroll"))
                .getResponse().getStatus()).isEqualTo(201);
    }

    private long createRequest(String experimentId, String participantId, String requestId) throws Exception {
        MvcResult result = createRequestRaw(experimentId, participantId, "audit needed", requestId);
        return readBody(result).get("unblindRequestId").asLong();
    }

    private MvcResult createRequestRaw(String experimentId, String participantId, String reason,
                                       String requestId) throws Exception {
        return postJson("/api/experiments/" + experimentId + "/unblind-requests",
                COORDINATOR, "COORDINATOR",
                Map.of("participantId", participantId, "reason", reason, "requestId", requestId));
    }

    private long approvedRequest(String experimentId, String participantId, String tag) throws Exception {
        setupEnrolledParticipant(experimentId, participantId);
        long id = createRequest(experimentId, participantId, "req-" + tag + "-ub");
        MvcResult approve = postJson(approvePath(experimentId, id), REVIEWER_A, "REVIEWER",
                Map.of("requestId", "req-" + tag + "-approve"));
        assertThat(approve.getResponse().getStatus()).isEqualTo(200);
        return id;
    }

    private String approvePath(String experimentId, long id) {
        return "/api/experiments/" + experimentId + "/unblind-requests/" + id + "/approve";
    }

    private String resultPath(String experimentId, long id) {
        return "/api/experiments/" + experimentId + "/unblind-requests/" + id + "/result";
    }

    private void createExperiment(String experimentId, int blockCount, String requestId) throws Exception {
        assertThat(postJson("/api/experiments", COORDINATOR, "COORDINATOR",
                Map.of("experimentId", experimentId, "blockCount", blockCount, "requestId", requestId))
                .getResponse().getStatus()).isEqualTo(201);
    }
}
