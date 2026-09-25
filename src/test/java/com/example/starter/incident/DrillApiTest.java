package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 演练沙盘 HTTP 层测试：创建标记、includeDrill 显式域选择、结果域标注、
 * 跨域引用 422、批次清理与清单/历史端点、默认查询只返回真实域。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DrillApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM notification_outbox");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM drill_cleanups");
        jdbc.update("DELETE FROM drill_batches");
        jdbc.update("DELETE FROM incidents");
    }

    private String reportDrill(String incidentKey, String drillKey, String batch) throws Exception {
        String body = "{\"incidentKey\":\"" + incidentKey + "\",\"severity\":\"S1\","
                + "\"summary\":\"s\",\"reporter\":\"r\",\"drillKey\":\"" + drillKey + "\""
                + (batch == null ? "" : ",\"drillBatch\":\"" + batch + "\"") + "}";
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domain").value("DRILL"))
                .andExpect(jsonPath("$.drillKey").value(drillKey));
        return body;
    }

    @Test
    void drillCreateAndDomainScopedQueries() throws Exception {
        // 真实与演练同键
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"INC-X\",\"severity\":\"S1\",\"summary\":\"s\","
                                + "\"reporter\":\"r\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domain").value("REAL"))
                .andExpect(jsonPath("$.drillKey").doesNotExist());
        reportDrill("INC-X", "drill-1", "batch-1");

        // 默认查询只返回真实域
        mvc.perform(get("/api/incidents/INC-X"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("REAL"));
        // 显式 includeDrill 返回演练域并标注
        mvc.perform(get("/api/incidents/INC-X").param("includeDrill", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("DRILL"))
                .andExpect(jsonPath("$.drillBatch").value("batch-1"));

        // 列表默认真实域；演练域仅含演练
        mvc.perform(get("/api/incidents"))
                .andExpect(jsonPath("$[?(@.incidentKey=='INC-X')].domain").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("REAL"))));
        mvc.perform(get("/api/incidents").param("includeDrill", "true"))
                .andExpect(jsonPath("$[?(@.incidentKey=='INC-X')].domain").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("DRILL"))));
    }

    @Test
    void drillOperationsRequireIncludeDrillFlag() throws Exception {
        reportDrill("INC-D", "drill-1", "batch-1");
        // 不带 includeDrill 的接管按真实域查找 -> 404
        mvc.perform(post("/api/incidents/INC-D/takeover").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK1\"}"))
                .andExpect(status().isNotFound());
        // 带标记后成功
        mvc.perform(post("/api/incidents/INC-D/takeover").header("X-Actor-Id", "alice")
                        .param("includeDrill", "true")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("DRILL"))
                .andExpect(jsonPath("$.status").value("COMMANDING"));
    }

    @Test
    void crossDomainBlocker_rejected422OverHttp() throws Exception {
        // 仅演练域存在前置事件
        reportDrill("INC-DEP", "drill-1", "batch-1");
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"INC-OWNER\",\"severity\":\"S1\",\"summary\":\"s\","
                                + "\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/INC-OWNER/takeover").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CKT\"}"))
                .andExpect(status().isOk());
        // 真实任务引用演练事件 -> 422
        mvc.perform(post("/api/incidents/INC-OWNER/tasks").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CKTASK\",\"taskKey\":\"T1\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":[\"INC-DEP\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CROSS_DOMAIN_REFERENCE"));
    }

    @Test
    void cleanupFlowOverHttp_listingAndHistory() throws Exception {
        reportDrill("INC-C1", "drill-http", "batch-http");
        mvc.perform(post("/api/incidents/INC-C1/takeover").header("X-Actor-Id", "alice")
                        .param("includeDrill", "true")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"K1\"}"))
                .andExpect(status().isOk());
        for (String target : new String[]{"CONTAINED", "RESOLVED", "CLOSED"}) {
            mvc.perform(post("/api/incidents/INC-C1/status").header("X-Actor-Id", "alice")
                            .param("includeDrill", "true")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"K-" + target + "\",\"targetStatus\":\""
                                    + target + "\"}"))
                    .andExpect(status().isOk());
        }
        // 批次清单
        mvc.perform(get("/api/incidents/drill-batches/batch-http/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // 提交清理
        mvc.perform(post("/api/incidents/drill-cleanups").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cleanupKey\":\"CLEAN-1\",\"batchKey\":\"batch-http\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedIncidents").value(1))
                .andExpect(jsonPath("$.status").value("CLEANED"));
        // 同键同参重放
        mvc.perform(post("/api/incidents/drill-cleanups").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cleanupKey\":\"CLEAN-1\",\"batchKey\":\"batch-http\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedIncidents").value(1));
        // 清理后事件 404、清单为空、历史可查
        mvc.perform(get("/api/incidents/INC-C1").param("includeDrill", "true"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/drill-batches/batch-http/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/incidents/drill-cleanups").param("batchKey", "batch-http"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cleanupKey").value("CLEAN-1"));
        // 批次已清理后再写演练事件 -> 404
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"INC-C2\",\"severity\":\"S1\",\"summary\":\"s\","
                                + "\"reporter\":\"r\",\"drillKey\":\"drill-http\","
                                + "\"drillBatch\":\"batch-http\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unfinishedBatchCleanup_422ListsIncident() throws Exception {
        reportDrill("INC-U1", "drill-u", "batch-u");
        mvc.perform(post("/api/incidents/drill-cleanups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cleanupKey\":\"CLEAN-U\",\"batchKey\":\"batch-u\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_TERMINAL"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("INC-U1")));
    }
}
