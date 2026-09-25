package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 疏散区域与高危任务门禁 HTTP 层测试：验证区域/豁免/阻断/派工/撤离路由、
 * X-Actor-Id 请求头约束及 400/404/409/422 错误语义与 details 结构化明细。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvacuationApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_task_zone_blocks");
        jdbc.update("DELETE FROM zone_exemptions");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM incident_zones");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void report(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
    }

    private void takeover(String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private String registerZone(String incidentKey, String actor, String grids,
                                String from, String to, String level) throws Exception {
        MvcResult result = mvc.perform(post("/api/incidents/{k}/zones", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"grids\":" + grids
                                + ",\"effectiveFrom\":\"" + from + "\",\"effectiveTo\":\"" + to
                                + "\",\"riskLevel\":\"" + level + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zoneKey").isString())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.zoneKey");
    }

    @Test
    void zoneHttpFlow() throws Exception {
        report("INC-600");
        takeover("INC-600", "alice");

        // 登记区域（网格规范化排序）
        String zoneKey = registerZone("INC-600", "alice", "[\"b2\",\"A1\"]",
                "2026-09-22T01:00:00Z", "2026-09-22T02:00:00Z", "HIGH");

        // 查询区域
        mvc.perform(get("/api/incidents/{k}/zones", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-600"))
                .andExpect(jsonPath("$.zones.length()").value(1))
                .andExpect(jsonPath("$.zones[0].zoneKey").value(zoneKey))
                .andExpect(jsonPath("$.zones[0].grids[0]").value("A1"))
                .andExpect(jsonPath("$.zones[0].grids[1]").value("B2"))
                .andExpect(jsonPath("$.zones[0].latest").value(true));

        // 签发豁免并查询豁免版本
        mvc.perform(post("/api/incidents/{k}/exemptions", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"zoneKey\":\"" + zoneKey + "\",\"reason\":\"关键抢修\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zoneVersion").value(1))
                .andExpect(jsonPath("$.valid").value(true));
        mvc.perform(get("/api/incidents/{k}/exemptions", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exemptions.length()").value(1))
                .andExpect(jsonPath("$.exemptions[0].taskKey").value("T-1"));

        // 阻断查询（当前无阻断）
        mvc.perform(get("/api/incidents/{k}/zone-blocks", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks.length()").value(0));
    }

    @Test
    void zoneHttpErrors() throws Exception {
        report("INC-601");
        takeover("INC-601", "alice");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/zones", "INC-601")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"grids\":[\"A1\"],"
                                + "\"effectiveFrom\":\"2026-09-22T01:00:00Z\","
                                + "\"effectiveTo\":\"2026-09-22T02:00:00Z\","
                                + "\"riskLevel\":\"HIGH\"}"))
                .andExpect(status().isBadRequest());
        // 非法窗口 → 400
        mvc.perform(post("/api/incidents/{k}/zones", "INC-601")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"grids\":[\"A1\"],"
                                + "\"effectiveFrom\":\"2026-09-22T02:00:00Z\","
                                + "\"effectiveTo\":\"2026-09-22T01:00:00Z\","
                                + "\"riskLevel\":\"HIGH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/zones", "INC-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"grids\":[\"A1\"],"
                                + "\"effectiveFrom\":\"2026-09-22T01:00:00Z\","
                                + "\"effectiveTo\":\"2026-09-22T02:00:00Z\","
                                + "\"riskLevel\":\"HIGH\"}"))
                .andExpect(status().isNotFound());

        // 同事件同等级窗口网格重叠 → 409
        registerZone("INC-601", "alice", "[\"A1\"]", "2026-09-22T01:00:00Z",
                "2026-09-22T02:00:00Z", "HIGH");
        mvc.perform(post("/api/incidents/{k}/zones", "INC-601")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"grids\":[\"A1\"],"
                                + "\"effectiveFrom\":\"2026-09-22T01:30:00Z\","
                                + "\"effectiveTo\":\"2026-09-22T03:00:00Z\","
                                + "\"riskLevel\":\"HIGH\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void dispatchAndEvacuateHttpFlow() throws Exception {
        report("INC-602");
        takeover("INC-602", "alice");
        // 先建高危任务（尚无区域），再登记立即生效的区域
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-602")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"高危作业\","
                                + "\"blockerIncidentKeys\":[],\"highRisk\":true,"
                                + "\"workGrids\":[\"A1\"],\"finalPosition\":\"P1\"}"))
                .andExpect(status().isOk());
        String zoneKey = registerZone("INC-602", "alice", "[\"A1\"]",
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", "HIGH");

        // 无豁免派工 → 422 且 details 可区分
        mvc.perform(post("/api/incidents/{k}/tasks/dispatch", "INC-602")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"items\":["
                                + "{\"taskKey\":\"T-1\",\"finalPosition\":null,"
                                + "\"resources\":[\"CRANE-1\"]}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.details[0]").isString());

        // 签发豁免后派工成功
        mvc.perform(post("/api/incidents/{k}/exemptions", "INC-602")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"zoneKey\":\"" + zoneKey + "\",\"reason\":\"关键抢修\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/tasks/dispatch", "INC-602")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"items\":["
                                + "{\"taskKey\":\"T-1\",\"finalPosition\":null,"
                                + "\"resources\":[\"CRANE-1\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.leasedResources[0]").value("CRANE-1"));

        // 撤离登记：进行中命中任务 → EVACUATED
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evacuate", "INC-602", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EVACUATED"))
                .andExpect(jsonPath("$.evacuatedBy").value("alice"));

        // EVACUATED 终态：完成 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-602", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void startHttpGate_blockedTask() throws Exception {
        report("INC-603");
        takeover("INC-603", "alice");
        // 先建高危任务，再登记立即生效的区域将其阻断
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-603")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"高危作业\","
                                + "\"blockerIncidentKeys\":[],\"highRisk\":true,"
                                + "\"workGrids\":[\"A1\"],\"finalPosition\":\"P1\"}"))
                .andExpect(status().isOk());
        registerZone("INC-603", "alice", "[\"A1\"]", "2020-01-01T00:00:00Z",
                "2030-01-01T00:00:00Z", "HIGH");

        // 任务已被阻断：开始 → 422
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-603", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EVACUATION_BLOCKED"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-603", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_ENTITY"));
        // 阻断查询返回快照
        mvc.perform(get("/api/incidents/{k}/zone-blocks", "INC-603"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks.length()").value(1))
                .andExpect(jsonPath("$.blocks[0].taskKey").value("T-1"))
                .andExpect(jsonPath("$.blocks[0].active").value(true));
    }
}
