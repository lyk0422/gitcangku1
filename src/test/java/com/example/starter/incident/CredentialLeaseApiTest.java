package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 资源资质租约 HTTP 层测试（真实 H2 内存库）：验证资质登记/撤销、批量租约、
 * 风险门禁、替换恢复、查询与 400/404/409/422 错误语义及结构化 details。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CredentialLeaseApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private Instant end;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM credential_risks");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        end = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(7200);
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

    private void highRiskTask(String incidentKey, String taskKey, String requiredJson)
            throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"G\",\"title\":\"高危\","
                                + "\"blockerIncidentKeys\":[],"
                                + "\"requiredCredentials\":" + requiredJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    private void register(String resource, String code, Instant validUntil) throws Exception {
        mvc.perform(post("/api/resources/{r}/credentials/{c}", resource, code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceId\":\"" + resource
                                + "\",\"credentialCode\":\"" + code + "\",\"validUntil\":\""
                                + validUntil + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void fullCredentialLeaseLifecycle_overHttp() throws Exception {
        report("INC-H1");
        takeover("INC-H1", "alice");
        highRiskTask("INC-H1", "T-1", "[\"HOTWORK\"]");

        // 未登记资质即分配 → 422，details 列出缺失资质
        String allocateBody = "{\"commandKey\":\"" + key() + "\",\"items\":[{"
                + "\"incidentKey\":\"INC-H1\",\"taskKey\":\"T-1\",\"resourceId\":\"CRANE-1\","
                + "\"leaseStart\":\"" + end.minusSeconds(3600) + "\",\"leaseEnd\":\"" + end
                + "\"}]}";
        mvc.perform(post("/api/leases").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(allocateBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_COVERED"))
                .andExpect(jsonPath("$.details.missing[0]").value("HOTWORK"))
                .andExpect(jsonPath("$.details.expired").isArray());

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON).content(allocateBody))
                .andExpect(status().isBadRequest());

        register("CRANE-1", "HOTWORK", end.plusSeconds(3600));

        // 资源资质查询
        mvc.perform(get("/api/resources/{r}/credentials", "CRANE-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentials[0].credentialCode").value("HOTWORK"));

        // 资质有效期不严格覆盖（validUntil 恰好等于 leaseEnd）→ 422 expired
        Instant boundary = end;
        mvc.perform(post("/api/resources/{r}/credentials/{c}", "CRANE-1", "HOTWORK")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"validUntil\":\"" + boundary + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(post("/api/leases").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(allocateBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.expired[0]").value("HOTWORK"));

        // 续期至严格覆盖后分配成功（version 3）
        mvc.perform(post("/api/resources/{r}/credentials/{c}", "CRANE-1", "HOTWORK")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"validUntil\":\""
                                + end.plusSeconds(3600) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        mvc.perform(post("/api/leases").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(allocateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created[0].resourceId").value("CRANE-1"))
                .andExpect(jsonPath("$.created[0].requiredCredentials[0]").value("HOTWORK"))
                .andExpect(jsonPath("$.created[0].current").value(true));

        // 开始
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-H1", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 撤销资质 → 风险
        mvc.perform(post("/api/resources/{r}/credentials/{c}/revoke", "CRANE-1", "HOTWORK")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"reason\":\"证件造假\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credential.status").value("REVOKED"))
                .andExpect(jsonPath("$.triggeredRisks[0].taskKey").value("T-1"));

        // 任务进入风险门禁，完成 422
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-H1", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 门禁原因与风险租约查询
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/gate", "INC-H1", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREDENTIAL_RISK"))
                .andExpect(jsonPath("$.credentialRisk").value(true))
                .andExpect(jsonPath("$.canComplete").value(false))
                .andExpect(jsonPath("$.reasons[0]").value(org.hamcrest.Matchers
                        .containsString("CREDENTIAL_RISK")));
        mvc.perform(get("/api/incidents/{k}/credential-risks", "INC-H1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risks.length()").value(1))
                .andExpect(jsonPath("$.risks[0].credentialCode").value("HOTWORK"));

        // 用不合格旧资源替换 → 422
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/lease/replace", "INC-H1", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"resourceId\":\"CRANE-1\",\"leaseStart\":\""
                                + end.minusSeconds(3600) + "\",\"leaseEnd\":\"" + end + "\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 合格新资源替换 → 恢复并完成
        register("CRANE-2", "HOTWORK", end.plusSeconds(3600));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/lease/replace", "INC-H1", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"resourceId\":\"CRANE-2\",\"leaseStart\":\""
                                + end.minusSeconds(3600) + "\",\"leaseEnd\":\"" + end + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value("CRANE-2"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-H1", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        // 风险记录不可变保留
        mvc.perform(get("/api/incidents/{k}/credential-risks", "INC-H1"))
                .andExpect(jsonPath("$.risks.length()").value(1));
    }

    @Test
    void credentialEndpoints_notFoundAndValidation() throws Exception {
        // 撤销不存在的资质 → 404
        mvc.perform(post("/api/resources/{r}/credentials/{c}/revoke", "NOPE", "C1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        // 登记缺少 validUntil → 400
        mvc.perform(post("/api/resources/{r}/credentials/{c}", "R", "C1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isBadRequest());
        // 风险查询不存在事件 → 404
        mvc.perform(get("/api/incidents/{k}/credential-risks", "INC-404"))
                .andExpect(status().isNotFound());
        // 门禁查询不存在任务 → 404
        report("INC-G1");
        takeover("INC-G1", "alice");
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/gate", "INC-G1", "T-X"))
                .andExpect(status().isNotFound());
    }
}
