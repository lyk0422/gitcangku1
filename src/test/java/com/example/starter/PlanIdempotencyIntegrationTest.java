package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 幂等与重新清场轮次集成测试。
 */
class PlanIdempotencyIntegrationTest extends IntegrationTestSupport {

    @Test
    void issueAndExecuteReplaySameParamsAndConflictOnDifferentParams() throws Exception {
        long planId = createThreePointPlan();
        confirmAll(planId);

        MvcResult first = issue(planId, "REQ-ISSUE-10", "班长王");
        assertEquals(201, first.getResponse().getStatus());
        long commandId = body(first).get("commandId").asLong();

        MvcResult replay = issue(planId, "REQ-ISSUE-10", "班长王");
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(commandId, body(replay).get("commandId").asLong());

        MvcResult conflict = issue(planId, "REQ-ISSUE-10", "班长李");
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals("IDEMPOTENT_CONFLICT", body(conflict).get("code").asText());

        MvcResult execFirst = execute(commandId, "REQ-EXEC-10", "班长王");
        assertEquals(201, execFirst.getResponse().getStatus());
        long reportId = body(execFirst).get("reportId").asLong();

        MvcResult execReplay = execute(commandId, "REQ-EXEC-10", "班长王");
        assertEquals(200, execReplay.getResponse().getStatus());
        assertEquals(reportId, body(execReplay).get("reportId").asLong());

        MvcResult execConflict = execute(commandId, "REQ-EXEC-10", "班长李");
        assertEquals(409, execConflict.getResponse().getStatus());
        assertEquals("IDEMPOTENT_CONFLICT", body(execConflict).get("code").asText());

        JsonNode detail = body(getJson("/api/plans/" + planId));
        assertEquals(1, detail.get("commands").size());
        assertEquals(1, detail.get("executions").size());
    }

    @Test
    void reclearanceRequiresNewRoundConfirmations() throws Exception {
        long planId = createThreePointPlan();
        confirmAll(planId);
        issue(planId, "REQ-ISSUE-11", "班长王");

        entry(planId, "巡查员");
        MvcResult cleared = postJson("/api/plans/" + planId + "/zone-clear",
                "{\"clearedBy\":\"班长王\"}");
        assertEquals(200, cleared.getResponse().getStatus());
        assertEquals(2, body(cleared).get("currentRound").asInt());

        // 旧轮次确认保留用于审计，但不可复用：直接签发被拒
        MvcResult rejected = issue(planId, "REQ-ISSUE-12", "班长王");
        assertEquals(422, rejected.getResponse().getStatus());
        assertEquals("CHECKPOINTS_INCOMPLETE", body(rejected).get("code").asText());

        JsonNode detail = body(getJson("/api/plans/" + planId));
        assertEquals(1, detail.get("checkpoints").get(0).get("confirmations").size());

        // 新一轮确认全部补齐后才能签发
        confirmAll(planId);
        MvcResult issued = issue(planId, "REQ-ISSUE-12", "班长王");
        assertEquals(201, issued.getResponse().getStatus());
        assertEquals(2, body(issued).get("round").asInt());
    }

    @Test
    void issueRejectedWhileZoneOccupied() throws Exception {
        long planId = createThreePointPlan();
        confirmAll(planId);
        entry(planId, "巡查员");
        MvcResult rejected = issue(planId, "REQ-ISSUE-13", "班长王");
        assertEquals(422, rejected.getResponse().getStatus());
        assertEquals("ZONE_OCCUPIED", body(rejected).get("code").asText());
    }
}