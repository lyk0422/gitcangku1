package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 主流程与失败分支集成测试（真实 H2 库，覆盖题干合成场景）。
 */
class PlanFlowIntegrationTest extends IntegrationTestSupport {

    @Test
    void fullFlowConfirmIssueExecuteAndQuery() throws Exception {
        long planId = createThreePointPlan();
        confirmAll(planId);

        MvcResult issued = issue(planId, "REQ-ISSUE-1", "班长王");
        assertEquals(201, issued.getResponse().getStatus());
        JsonNode command = body(issued);
        long commandId = command.get("commandId").asLong();
        assertEquals("ACTIVE", command.get("status").asText());
        assertEquals("2026-01-01T00:05:00Z", command.get("expiresAt").asText());

        MvcResult executed = execute(commandId, "REQ-EXEC-1", "班长王");
        assertEquals(201, executed.getResponse().getStatus());
        assertEquals("COMPLETED", body(executed).get("planStatus").asText());

        JsonNode detail = body(getJson("/api/plans/" + planId));
        assertEquals("COMPLETED", detail.get("status").asText());
        assertEquals(1, detail.get("currentRound").asInt());
        assertEquals(3, detail.get("checkpoints").size());
        assertEquals("2026-01-01T00:00:00Z",
                detail.get("checkpoints").get(0).get("confirmations").get(0)
                        .get("confirmedAt").asText());
        assertEquals("EXECUTED", detail.get("commands").get(0).get("status").asText());
        assertEquals(1, detail.get("executions").size());
    }

    @Test
    void issueRejectedWhenOnlyTwoOfThreeConfirmedThenAllowedAfterThird() throws Exception {
        long planId = createThreePointPlan();
        confirm(planId, "CP-N");
        confirm(planId, "CP-E");

        MvcResult rejected = issue(planId, "REQ-ISSUE-2", "班长王");
        assertEquals(422, rejected.getResponse().getStatus());
        assertEquals("CHECKPOINTS_INCOMPLETE", body(rejected).get("code").asText());

        // 失败不占幂等键：补齐第三点后可用同一 requestId 成功签发
        confirm(planId, "CP-S");
        MvcResult issued = issue(planId, "REQ-ISSUE-2", "班长王");
        assertEquals(201, issued.getResponse().getStatus());
        assertEquals("ACTIVE", body(issued).get("status").asText());
    }

    @Test
    void entryAtMinute4InvalidatesCommandAndExecutionRejected() throws Exception {
        long planId = createThreePointPlan();
        confirmAll(planId);
        long commandId = body(issue(planId, "REQ-ISSUE-3", "班长王")).get("commandId").asLong();

        clock.plusMinutes(4);
        MvcResult entryResult = entry(planId, "巡查员");
        assertEquals(201, entryResult.getResponse().getStatus());
        assertEquals(2, body(entryResult).get("newRound").asInt());

        MvcResult executed = execute(commandId, "REQ-EXEC-3", "班长王");
        assertEquals(422, executed.getResponse().getStatus());
        assertEquals("COMMAND_INVALIDATED", body(executed).get("code").asText());

        JsonNode detail = body(getJson("/api/plans/" + planId));
        assertEquals("INVALIDATED", detail.get("commands").get(0).get("status").asText());
        assertEquals(0, detail.get("executions").size());
        assertTrue(detail.get("occupied").asBoolean());
    }

    @Test
    void issueRejectedWhenConfirmationOlderThan15Minutes() throws Exception {
        long planId = createThreePointPlan();
        confirmAll(planId);
        clock.plusMinutes(16);
        MvcResult rejected = issue(planId, "REQ-ISSUE-4", "班长王");
        assertEquals(422, rejected.getResponse().getStatus());
        assertEquals("CONFIRMATION_STALE", body(rejected).get("code").asText());
    }

    @Test
    void executionRejectedWhenCommandExpired() throws Exception {
        long planId = createThreePointPlan();
        confirmAll(planId);
        long commandId = body(issue(planId, "REQ-ISSUE-5", "班长王")).get("commandId").asLong();
        clock.plusMinutes(6);
        MvcResult executed = execute(commandId, "REQ-EXEC-5", "班长王");
        assertEquals(422, executed.getResponse().getStatus());
        assertEquals("COMMAND_EXPIRED", body(executed).get("code").asText());
    }

    @Test
    void nonZeroHeadcountConfirmationRejected() throws Exception {
        long planId = createThreePointPlan();
        MvcResult result = postJson("/api/plans/" + planId + "/confirmations",
                "{\"checkpointCode\":\"CP-N\",\"confirmedBy\":\"安全员甲\",\"headcount\":2}");
        assertEquals(422, result.getResponse().getStatus());
        assertEquals("HEADCOUNT_NOT_ZERO", body(result).get("code").asText());
    }
}