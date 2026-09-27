package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 处置提案 HTTP 层测试：验证提案/投票/撤回/修订/诊断路由、410 到期语义、
 * 422 登记校验、409 冲突、角色集合规范化、计票差额与只读接口不改状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class DecisionApiTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private IncidentService incidentService;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM decision_votes");
        jdbc.update("DELETE FROM decision_required_roles");
        jdbc.update("DELETE FROM incident_decisions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "s", "r"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void task(String incidentKey, String taskKey) {
        incidentService.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), taskKey, "DB", "扩容", java.util.List.of()));
    }

    @Test
    void decisionHttpFlow() throws Exception {
        commanding("INC-600", "alice");
        task("INC-600", "T-1");

        // 登记提案
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"decisionType\":\"TASK_COMPLETE\","
                                + "\"expiresAt\":\"2026-09-22T00:10:00Z\","
                                + "\"requiredRoles\":[\"SRE\",\"OPS\",\"DB\"],\"quorum\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.requiredRoles[0]").value("DB"))
                .andExpect(jsonPath("$.requiredRoles[1]").value("OPS"))
                .andExpect(jsonPath("$.requiredRoles[2]").value("SRE"))
                .andExpect(jsonPath("$.baseTaskRevision").value(1))
                .andExpect(jsonPath("$.remainingVotes").value(2));

        // 列表与明细
        mvc.perform(get("/api/incidents/{k}/decisions", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-600"))
                .andExpect(jsonPath("$.proposals.length()").value(1));
        mvc.perform(get("/api/incidents/{k}/decisions/{p}", "INC-600", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalKey").value("P-1"));

        // 诊断：实际票 0、要求 2、差额 2、缺失三个角色，只读
        mvc.perform(get("/api/incidents/{k}/decisions/{p}/diagnostics", "INC-600", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.votingOpen").value(true))
                .andExpect(jsonPath("$.secondsRemaining").value(600))
                .andExpect(jsonPath("$.supportVotes").value(0))
                .andExpect(jsonPath("$.quorum").value(2))
                .andExpect(jsonPath("$.remainingVotes").value(2))
                .andExpect(jsonPath("$.requiredRoleCount").value(3))
                .andExpect(jsonPath("$.missingRoles.length()").value(3));

        // 第一张支持票：未达人数
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes", "INC-600", "P-1")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"roleCode\":\"OPS\","
                                + "\"choice\":\"SUPPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.supportVotes").value(1))
                .andExpect(jsonPath("$.remainingVotes").value(1));

        // 同角色重复投 → 409
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes", "INC-600", "P-1")
                        .header("X-Actor-Id", "u2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"roleCode\":\"OPS\","
                                + "\"choice\":\"OPPOSE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 第二张支持票达人数：生效，任务完成
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes", "INC-600", "P-1")
                        .header("X-Actor-Id", "u3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"roleCode\":\"DB\","
                                + "\"choice\":\"SUPPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.effectiveBy").value("u3"))
                .andExpect(jsonPath("$.effectiveTaskRevision").value(1))
                .andExpect(jsonPath("$.votes.length()").value(2));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.doneBy").value("u3"));
    }

    @Test
    void decisionHttpExpiry_410() throws Exception {
        commanding("INC-610", "alice");
        task("INC-610", "T-1");
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"decisionType\":\"TASK_CANCEL\","
                                + "\"expiresAt\":\"2026-09-22T00:05:00Z\","
                                + "\"requiredRoles\":[\"OPS\"],\"quorum\":1}"))
                .andExpect(status().isCreated());

        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-22T00:05:00Z"));
        // 到期当刻投票 → 410，提案转 EXPIRED
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes", "INC-610", "P-1")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"roleCode\":\"OPS\","
                                + "\"choice\":\"SUPPORT\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("GONE"));
        mvc.perform(get("/api/incidents/{k}/decisions/{p}", "INC-610", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.expiredAt").value("2026-09-22T00:05:00Z"));
        // 到期后撤回 → 410
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/withdraw", "INC-610", "P-1")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isGone());
    }

    @Test
    void decisionHttpValidation_422And400() throws Exception {
        commanding("INC-620", "alice");
        task("INC-620", "T-1");
        // 角色集合为空 → 422
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-620")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"decisionType\":\"TASK_CANCEL\","
                                + "\"expiresAt\":\"2026-09-22T00:10:00Z\","
                                + "\"requiredRoles\":[],\"quorum\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PROPOSAL"));
        // 法定人数超过角色数 → 422，消息含实际值/要求值/差额
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-620")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-2\","
                                + "\"taskKey\":\"T-1\",\"decisionType\":\"TASK_CANCEL\","
                                + "\"expiresAt\":\"2026-09-22T00:10:00Z\","
                                + "\"requiredRoles\":[\"OPS\"],\"quorum\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("差额: 2")));
        // 到期早于当前时钟 → 422
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-620")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-3\","
                                + "\"taskKey\":\"T-1\",\"decisionType\":\"TASK_CANCEL\","
                                + "\"expiresAt\":\"2026-09-21T00:10:00Z\","
                                + "\"requiredRoles\":[\"OPS\"],\"quorum\":1}"))
                .andExpect(status().isUnprocessableEntity());
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-620")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-4\","
                                + "\"taskKey\":\"T-1\",\"decisionType\":\"TASK_CANCEL\","
                                + "\"expiresAt\":\"2026-09-22T00:10:00Z\","
                                + "\"requiredRoles\":[\"OPS\"],\"quorum\":1}"))
                .andExpect(status().isBadRequest());
        // 不存在的事件/提案/任务路由
        mvc.perform(get("/api/incidents/{k}/decisions", "INC-404"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/decisions/{p}", "INC-620", "P-404"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviseHttp_stalesVotes() throws Exception {
        commanding("INC-630", "alice");
        task("INC-630", "T-1");
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-630")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"decisionType\":\"TASK_COMPLETE\","
                                + "\"expiresAt\":\"2026-09-22T00:10:00Z\","
                                + "\"requiredRoles\":[\"OPS\",\"DB\"],\"quorum\":2}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes", "INC-630", "P-1")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"roleCode\":\"OPS\","
                                + "\"choice\":\"SUPPORT\"}"))
                .andExpect(status().isOk());

        // 修订：版本 +1，旧票 STALE
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/revise", "INC-630", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"title\":\"修订标题\","
                                + "\"note\":\"修订说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskKey").value("T-1"))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(get("/api/incidents/{k}/decisions/{p}", "INC-630", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskRevision").value(2))
                .andExpect(jsonPath("$.baseTaskRevision").value(1))
                .andExpect(jsonPath("$.supportVotes").value(0))
                .andExpect(jsonPath("$.remainingVotes").value(2))
                .andExpect(jsonPath("$.votes[0].status").value("STALE"))
                .andExpect(jsonPath("$.votes[0].taskRevision").value(1))
                .andExpect(jsonPath("$.votes[0].staleAt").exists());
    }
}
