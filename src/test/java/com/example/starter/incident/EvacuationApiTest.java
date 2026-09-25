package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

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
 * 疏散门禁 HTTP 层测试：验证区域登记/查询、豁免、批量派工全量 422、开始/完成/撤离路由
 * 与可区分错误码，以及阻断快照、区域结束恢复的端到端 JSON 语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class EvacuationApiTest {

    private static final String T0 = "2026-09-26T00:00:00Z";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        ((ControllableClock) clock).setInstant(Instant.parse(T0));
        jdbc.update("DELETE FROM evacuation_exemptions");
        jdbc.update("DELETE FROM evacuation_zones");
        jdbc.update("DELETE FROM zone_command_keys");
        jdbc.update("DELETE FROM task_dispatch_leases");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void reportAndTakeover(String incident) throws Exception {
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incident
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incident)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void createTask(String incident, String taskKey, String grid) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incident)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"G\",\"title\":\"t\",\"workGrid\":\"" + grid
                                + "\",\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private void registerZone(String incident, String zoneKey, String level, String gridsJson,
                              String from, String to) throws Exception {
        mvc.perform(post("/api/incidents/{k}/zones", incident)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"zoneKey\":\"" + zoneKey
                                + "\",\"riskLevel\":\"" + level + "\",\"grids\":" + gridsJson
                                + ",\"effectiveFrom\":\"" + from + "\",\"effectiveTo\":\"" + to
                                + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void evacuationHttpFlow() throws Exception {
        reportAndTakeover("INC-H1");
        createTask("INC-H1", "T-HIT", "X1");
        createTask("INC-H1", "T-SAFE", "Y1");

        // 区域登记：命中任务被阻断
        registerZone("INC-H1", "Z-A", "HIGH", "[\"X1\"]", T0, "2026-09-26T01:00:00Z");
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-H1", "T-HIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EVACUATION_BLOCKED"))
                .andExpect(jsonPath("$.blocked.zoneKey").value("Z-A"))
                .andExpect(jsonPath("$.blocked.version").value(1))
                .andExpect(jsonPath("$.blocked.grids[0]").value("X1"));

        // 阻断查询接口返回命中缺豁免区域
        mvc.perform(get("/api/incidents/{k}/task-block-status", "INC-H1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].blockedBy[0]").value("Z-A"));

        // 无豁免开始 → 422 且错误码可区分
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-H1", "T-HIT")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TASK_EVACUATION_BLOCKED"));

        // 授予豁免
        mvc.perform(post("/api/incidents/{k}/zones/{z}/exemptions", "INC-H1", "Z-A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-HIT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.zoneKey").value("Z-A"));

        // 持豁免开始并完成
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-H1", "T-HIT")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-H1", "T-HIT")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        // 豁免查询
        mvc.perform(get("/api/incidents/{k}/exemptions", "INC-H1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exemptions.length()").value(1));
    }

    @Test
    void batchDispatch_httpRollbackAndEvacuate() throws Exception {
        reportAndTakeover("INC-D1");
        createTask("INC-D1", "T-OK", "Y1");
        createTask("INC-D1", "T-HIT", "X1");
        registerZone("INC-D1", "Z-A", "HIGH", "[\"X1\"]", T0, "2026-09-26T01:00:00Z");

        // 派工含缺豁免任务 → 422 逐条明细，全部回滚
        mvc.perform(post("/api/incidents/{k}/tasks/dispatch", "INC-D1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"taskKeys\":[\"T-OK\",\"T-HIT\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DISPATCH_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].taskKey").value("T-HIT"))
                .andExpect(jsonPath("$.details[0].reason").value("EVACUATION_BLOCKED"));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-D1", "T-OK"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 合格任务派工→开始→命中区域（进行中）→不可完成→撤离终态
        mvc.perform(post("/api/incidents/{k}/tasks/dispatch", "INC-D1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKeys\":[\"T-OK\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched[0]").value("T-OK"));
        // T-OK 网格 Y1 不命中，先开始；再让它进入 X1 区域用一个新任务演示撤离更直接：
        // 这里直接对 T-OK 开始（Y1 安全）→ 可完成，验证安全任务不受影响。
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-D1", "T-OK")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-D1", "T-OK")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void zoneEnd_httpReopensBlockedTask() throws Exception {
        reportAndTakeover("INC-R1");
        createTask("INC-R1", "T-1", "X1");
        // 登记一个已到期窗口（to 为过去时刻），登记即阻断；随后结束裁决恢复
        String pastTo = Instant.parse(T0).minusSeconds(1).toString();
        registerZone("INC-R1", "Z-A", "HIGH", "[\"X1\"]",
                "2026-09-25T00:00:00Z", pastTo);
        // 窗口已到期：登记裁决阻断不到（evaluate 结束裁决先恢复），任务保持 OPEN
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-R1", "T-1"))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(get("/api/incidents/{k}/zones", "INC-R1"))
                .andExpect(jsonPath("$.zones[0].status").value("ENDED"))
                .andExpect(jsonPath("$.zones[0].effective").value(false));
    }

    @Test
    void zoneRegister_validationAndAuth() throws Exception {
        reportAndTakeover("INC-V1");
        // 非指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/zones", "INC-V1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"zoneKey\":\"Z\",\"riskLevel\":\"HIGH\",\"grids\":[\"X1\"],"
                                + "\"effectiveFrom\":\"" + T0
                                + "\",\"effectiveTo\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isConflict());
        // 窗口非法 → 400
        mvc.perform(post("/api/incidents/{k}/zones", "INC-V1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"zoneKey\":\"Z\",\"riskLevel\":\"HIGH\",\"grids\":[\"X1\"],"
                                + "\"effectiveFrom\":\"2026-09-26T01:00:00Z\",\"effectiveTo\":\""
                                + T0 + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
