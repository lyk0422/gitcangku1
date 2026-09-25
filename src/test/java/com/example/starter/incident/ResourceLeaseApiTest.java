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
 * 资源资质与租约 HTTP 层测试：验证资源/资质/租约路由、X-Actor-Id 请求头约束、
 * 422 CREDENTIAL_VIOLATION 错误体与 details 明细、门禁与风险租约查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class ResourceLeaseApiTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM credential_risk_records");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM task_required_credentials");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM resources");
        jdbc.update("DELETE FROM lease_domain_lock");
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

    private void commanding(String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void registerResource(String resourceKey) throws Exception {
        mvc.perform(post("/api/resources")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\""
                                + resourceKey + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resourceKey").value(resourceKey))
                .andExpect(jsonPath("$.version").value(1));
    }

    private void registerCredential(String resourceKey, String code, String validFrom,
                                    String validUntil) throws Exception {
        mvc.perform(post("/api/resources/{k}/credentials", resourceKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"credentialCode\":\"" + code
                                + "\",\"validFrom\":\"" + validFrom + "\",\"validUntil\":\""
                                + validUntil + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialCode").value(code))
                .andExpect(jsonPath("$.revoked").value(false));
    }

    private void createHighRiskTask(String incidentKey, String actor, String taskKey,
                                    String plannedCompleteAt, String codesJson) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"DB\",\"title\":\"高危处置\","
                                + "\"blockerIncidentKeys\":[],"
                                + "\"requiredCredentials\":" + codesJson + ","
                                + "\"plannedCompleteAt\":\"" + plannedCompleteAt + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskKey").value(taskKey))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void fullFlow_assignRevokeGateReplace() throws Exception {
        commanding("INC-API1", "alice");
        registerResource("RES-API");
        registerCredential("RES-API", "FIRE-A", "2026-09-22T00:00:00Z", "2026-09-23T00:00:00Z");
        createHighRiskTask("INC-API1", "alice", "T-1", "2026-09-22T12:00:00Z", "[\"FIRE-A\"]");

        // 资质查询
        mvc.perform(get("/api/resources/{k}/credentials", "RES-API"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value("RES-API"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.credentials[0].credentialCode").value("FIRE-A"));

        // 批量租约分配
        mvc.perform(post("/api/leases")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseKey\":\"LK-API1\",\"resourceKey\":\"RES-API\","
                                + "\"tasks\":[{\"incidentKey\":\"INC-API1\",\"taskKey\":\"T-1\"}],"
                                + "\"leaseStart\":\"2026-09-22T00:00:00Z\","
                                + "\"leaseEnd\":\"2026-09-22T23:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseKey").value("LK-API1"))
                .andExpect(jsonPath("$.leases[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.leases[0].credentialCodes[0]").value("FIRE-A"));

        // 撤销资质 → 任务进入 CREDENTIAL_RISK，门禁拒绝开始
        mvc.perform(post("/api/resources/{k}/credentials/{c}/revoke", "RES-API", "FIRE-A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/gate", "INC-API1", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREDENTIAL_RISK"))
                .andExpect(jsonPath("$.canStart").value(false))
                .andExpect(jsonPath("$.canComplete").value(false))
                .andExpect(jsonPath("$.riskCredentials[0]").value("FIRE-A"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-API1", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 风险租约查询（按资源与按事件）
        mvc.perform(get("/api/resources/{k}/risk-leases", "RES-API"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLeases[0].lease.status").value("CREDENTIAL_RISK"))
                .andExpect(jsonPath("$.riskLeases[0].riskRecords[0].credentialCode")
                        .value("FIRE-A"));
        mvc.perform(get("/api/incidents/{k}/risk-leases", "INC-API1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLeases[0].lease.taskKey").value("T-1"));

        // 合格资源替换 → 任务恢复，可开始
        registerResource("RES-API2");
        registerCredential("RES-API2", "FIRE-A", "2026-09-22T00:00:00Z", "2026-09-23T00:00:00Z");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/lease/replace", "INC-API1", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseKey\":\"LK-API2\",\"resourceKey\":\"RES-API2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.resourceKey").value("RES-API2"));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/gate", "INC-API1", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.canStart").value(true));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-API1", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void assignLeases_credentialViolationErrorBody() throws Exception {
        commanding("INC-API2", "alice");
        registerResource("RES-NO");
        createHighRiskTask("INC-API2", "alice", "T-1", "2026-09-22T12:00:00Z",
                "[\"FIRE-A\",\"FIRE-B\"]");

        // 资质缺失 → 422 CREDENTIAL_VIOLATION，details 列出缺失资质
        mvc.perform(post("/api/leases")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseKey\":\"LK-NO1\",\"resourceKey\":\"RES-NO\","
                                + "\"tasks\":[{\"incidentKey\":\"INC-API2\",\"taskKey\":\"T-1\"}],"
                                + "\"leaseStart\":\"2026-09-22T00:00:00Z\","
                                + "\"leaseEnd\":\"2026-09-22T23:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_VIOLATION"))
                .andExpect(jsonPath("$.details[0].credentialCode").value("FIRE-A"))
                .andExpect(jsonPath("$.details[0].issue").value("MISSING"))
                .andExpect(jsonPath("$.details[1].credentialCode").value("FIRE-B"));
    }

    @Test
    void writeEndpoints_requireActorHeader() throws Exception {
        mvc.perform(post("/api/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"RES-H\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseKey\":\"LK-H\",\"resourceKey\":\"RES-H\",\"tasks\":[],"
                                + "\"leaseStart\":\"2026-09-22T00:00:00Z\","
                                + "\"leaseEnd\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/lease/replace", "INC-X", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseKey\":\"LK-H2\",\"resourceKey\":\"RES-H\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void queryEndpoints_notFound() throws Exception {
        mvc.perform(get("/api/resources/{k}/credentials", "RES-404"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/resources/{k}/risk-leases", "RES-404"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/risk-leases", "INC-404"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/gate", "INC-404", "T-1"))
                .andExpect(status().isNotFound());
    }
}
