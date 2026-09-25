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

/**
 * 演练沙盘 HTTP 层测试：验证域路由、查询默认域与 includeDrill、结果 domain 标注、
 * 跨域依赖 422、批次清理 422/200 与批次清单/清理历史的 REST 语义。
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
        jdbc.update("DELETE FROM incident_notifications");
        jdbc.update("DELETE FROM incident_dependencies");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM drill_batches");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private void reportReal(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey + "\",\"severity\":\"S2\","
                                + "\"summary\":\"real\",\"reporter\":\"rr\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domain").value("REAL"));
    }

    private void reportDrill(String batchKey, String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey + "\",\"severity\":\"S2\","
                                + "\"summary\":\"drill\",\"reporter\":\"dr\",\"drillKey\":\"sandbox\","
                                + "\"batchKey\":\"" + batchKey + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domain").value("DRILL"))
                .andExpect(jsonPath("$.drillBatchKey").value(batchKey));
    }

    private void commanding(String urlPrefix, String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents/{k}/takeover" + urlPrefix, incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMANDING"));
    }

    @Test
    void drillIsolationAndQueryMarking() throws Exception {
        reportReal("API-SAME");
        reportDrill("BATCH-API", "API-SAME");

        // 默认清单只有真实域
        mvc.perform(get("/api/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].domain").value("REAL"));
        // includeDrill 才看到演练域，且标注 domain
        mvc.perform(get("/api/incidents").param("includeDrill", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.domain=='DRILL')].incidentKey").value("API-SAME"));
        // 默认真实域查询不到演练事件；显式 drill=true 可查
        mvc.perform(get("/api/incidents/{k}", "API-SAME"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.domain").value("REAL"));
        mvc.perform(get("/api/incidents/{k}", "API-SAME").param("drill", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.domain").value("DRILL"));
        // 统计默认仅真实域，includeDrill 附带演练域
        mvc.perform(get("/api/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.REAL.total").value(1))
                .andExpect(jsonPath("$.DRILL").doesNotExist());
        mvc.perform(get("/api/stats").param("includeDrill", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.DRILL.total").value(1));
    }

    @Test
    void crossDomainDependency_422() throws Exception {
        reportReal("API-R");
        commanding("", "API-R", "alice");
        reportDrill("BATCH-API2", "API-D");
        commanding("?drill=true", "API-D", "alice");

        // 真实事件依赖演练事件 → 422
        mvc.perform(post("/api/incidents/{k}/dependencies", "API-R")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"blockedByIncidentKey\":\"API-D\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CROSS_DOMAIN_REFERENCE"));
        // 演练事件依赖真实事件 → 422
        mvc.perform(post("/api/incidents/{k}/dependencies", "API-D").param("drill", "true")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"blockedByIncidentKey\":\"API-R\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CROSS_DOMAIN_REFERENCE"));
    }

    @Test
    void drillEscalation_noRealNotification_andCleanupFlow() throws Exception {
        String batch = "BATCH-WORKFLOW";
        reportDrill(batch, "WF-1");
        commanding("?drill=true", "WF-1", "alice");
        // 演练升级：域内生效，不产生真实副作用（无通知端点；以可再次查询与无 5xx 验证路径，
        // 副作用隔离由 DrillSandboxTest 直查 H2 断言）
        mvc.perform(post("/api/incidents/{k}/escalate", "WF-1").param("drill", "true")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"toSeverity\":\"S1\","
                                + "\"reason\":\"drill escalate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("S1"))
                .andExpect(jsonPath("$.domain").value("DRILL"));

        // 批次中还有未终结事件 WF-1（COMMANDING）：清理整批 422 并列出未终结事件
        String cleanupKey = key();
        mvc.perform(post("/api/drill/cleanups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cleanupKey\":\"" + cleanupKey + "\",\"batchKey\":\"" + batch + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_TERMINAL"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("WF-1")));

        // 推进到 RESOLVED 后同 cleanupKey（失败不占键）重试成功
        mvc.perform(post("/api/incidents/{k}/status", "WF-1").param("drill", "true")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/status", "WF-1").param("drill", "true")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"RESOLVED\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/drill/cleanups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cleanupKey\":\"" + cleanupKey + "\",\"batchKey\":\"" + batch + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedIncidentCount").value(1))
                .andExpect(jsonPath("$.batchKey").value(batch));

        // 清理后演练事件 404；批次清单含墓碑
        mvc.perform(get("/api/incidents/{k}", "WF-1").param("drill", "true"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/drill/batches/{b}", batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleaned").value(true))
                .andExpect(jsonPath("$.incidents.length()").value(0));
        mvc.perform(get("/api/drill/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchKey").value(batch));
    }
}
