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
 * 互助交接 HTTP 层测试：验证资源/代理人/交接/结算/责任/关闭阻断路由、
 * X-Actor-Id 请求头约束及 400/404/409/422 可区分错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResourceHandoffApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM handoff_task_refs");
        jdbc.update("DELETE FROM handoff_settlements");
        jdbc.update("DELETE FROM resource_handoff_items");
        jdbc.update("DELETE FROM resource_handoffs");
        jdbc.update("DELETE FROM incident_delegates");
        jdbc.update("DELETE FROM incident_resources");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
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

    private long versionOf(String incidentKey) throws Exception {
        String body = mvc.perform(get("/api/incidents/{k}", incidentKey))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String marker = "\"version\":";
        int start = body.indexOf(marker) + marker.length();
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return Long.parseLong(body.substring(start, end));
    }

    private void acquire(String incidentKey, String actor, String resourceKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/resources", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\""
                                + resourceKey + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resourceKey").value(resourceKey))
                .andExpect(jsonPath("$.holderIncidentKey").value(incidentKey));
    }

    private String handoffBody(String handoffKey, String target, String receiver,
                               long sourceVersion, long targetVersion, String resourceKey,
                               String leaseEnd) {
        return "{\"handoffKey\":\"" + handoffKey + "\",\"targetIncidentKey\":\"" + target
                + "\",\"receiver\":\"" + receiver + "\",\"sourceVersion\":" + sourceVersion
                + ",\"targetVersion\":" + targetVersion
                + ",\"leaseStart\":\"2026-01-01T00:00:00Z\",\"leaseEnd\":\"" + leaseEnd + "\""
                + ",\"items\":[{\"resourceKey\":\"" + resourceKey + "\",\"taskKeys\":[]}]}";
    }

    @Test
    void handoffHttpFlow() throws Exception {
        report("INC-S");
        report("INC-T");
        takeover("INC-S", "alice");
        takeover("INC-T", "bob");

        // 登记资源与代理人
        acquire("INC-S", "alice", "RES-1");
        mvc.perform(get("/api/incidents/{k}/resources", "INC-S"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(1));
        mvc.perform(post("/api/incidents/{k}/delegates", "INC-T")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"delegate\":\"carol\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.delegate").value("carol"));
        mvc.perform(get("/api/incidents/{k}/delegates", "INC-T"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delegates.length()").value(1));

        // 创建交接（接收人为登记代理人）
        String handoffKey = "HO-" + UUID.randomUUID();
        mvc.perform(post("/api/incidents/{k}/handoffs", "INC-S")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoffBody(handoffKey, "INC-T", "carol",
                                versionOf("INC-S"), versionOf("INC-T"), "RES-1",
                                "2099-01-01T00:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.handoffKey").value(handoffKey))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.receiver").value("carol"))
                .andExpect(jsonPath("$.items[0].resourceKey").value("RES-1"));

        // 资源责任：借出期间归目标事件
        mvc.perform(get("/api/resources/{r}/responsibility", "RES-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holderIncidentKey").value("INC-S"))
                .andExpect(jsonPath("$.responsibleIncidentKey").value("INC-T"))
                .andExpect(jsonPath("$.lentOut").value(true))
                .andExpect(jsonPath("$.handoffKey").value(handoffKey));

        // 来源关闭阻断原因
        mvc.perform(get("/api/incidents/{k}/close-blockers", "INC-S"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closeable").value(false))
                .andExpect(jsonPath("$.blockers[0].type").value("LENT_RESOURCE"))
                .andExpect(jsonPath("$.blockers[0].resourceKey").value("RES-1"));

        // 交接列表（目标侧）
        mvc.perform(get("/api/incidents/{k}/handoffs", "INC-T"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoffs.length()").value(1))
                .andExpect(jsonPath("$.handoffs[0].handoffKey").value(handoffKey));

        // 租约到期结算（另行创建一个已到期交接）
        acquire("INC-S", "alice", "RES-2");
        String expiredKey = "HO-" + UUID.randomUUID();
        mvc.perform(post("/api/incidents/{k}/handoffs", "INC-S")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoffBody(expiredKey, "INC-T", "bob",
                                versionOf("INC-S"), versionOf("INC-T"), "RES-2",
                                "2026-01-02T00:00:00Z")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/handoffs/settle", "INC-T")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlements.length()").value(1))
                .andExpect(jsonPath("$.settlements[0].reason").value("LEASE_EXPIRED"))
                .andExpect(jsonPath("$.settlements[0].resourceKey").value("RES-2"))
                .andExpect(jsonPath("$.settlements[0].returnedToIncidentKey").value("INC-S"));

        // 结算查询与归还后责任
        mvc.perform(get("/api/incidents/{k}/settlements", "INC-S"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlements.length()").value(1));
        mvc.perform(get("/api/resources/{r}/responsibility", "RES-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responsibleIncidentKey").value("INC-S"))
                .andExpect(jsonPath("$.lentOut").value(false));
    }

    @Test
    void handoffHttpErrors() throws Exception {
        report("INC-S");
        report("INC-T");
        takeover("INC-S", "alice");
        takeover("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/resources", "INC-S")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"RES-9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // 目标等于来源 → 400
        mvc.perform(post("/api/incidents/{k}/handoffs", "INC-S")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoffBody("HO-" + UUID.randomUUID(), "INC-S", "alice",
                                versionOf("INC-S"), versionOf("INC-S"), "RES-1",
                                "2099-01-01T00:00:00Z")))
                .andExpect(status().isBadRequest());

        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/handoffs", "INC-X")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoffBody("HO-" + UUID.randomUUID(), "INC-T", "bob",
                                1, 1, "RES-1", "2099-01-01T00:00:00Z")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/resources/{r}/responsibility", "RES-X"))
                .andExpect(status().isNotFound());

        // 非来源指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/handoffs", "INC-S")
                        .header("X-Actor-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoffBody("HO-" + UUID.randomUUID(), "INC-T", "bob",
                                versionOf("INC-S"), versionOf("INC-T"), "RES-1",
                                "2099-01-01T00:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 版本不匹配 → 409 VERSION_MISMATCH
        mvc.perform(post("/api/incidents/{k}/handoffs", "INC-S")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoffBody("HO-" + UUID.randomUUID(), "INC-T", "bob",
                                versionOf("INC-S") + 1, versionOf("INC-T"), "RES-1",
                                "2099-01-01T00:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));

        // 接收人无权限 → 422 RECEIVER_NOT_AUTHORIZED
        mvc.perform(post("/api/incidents/{k}/handoffs", "INC-S")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoffBody("HO-" + UUID.randomUUID(), "INC-T", "mallory",
                                versionOf("INC-S"), versionOf("INC-T"), "RES-1",
                                "2099-01-01T00:00:00Z")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECEIVER_NOT_AUTHORIZED"));

        // 成交后同资源再借 → 422 LEASE_OVERLAP
        mvc.perform(post("/api/incidents/{k}/handoffs", "INC-S")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoffBody("HO-" + UUID.randomUUID(), "INC-T", "bob",
                                versionOf("INC-S"), versionOf("INC-T"), "RES-1",
                                "2099-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/handoffs", "INC-S")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoffBody("HO-" + UUID.randomUUID(), "INC-T", "bob",
                                versionOf("INC-S"), versionOf("INC-T"), "RES-1",
                                "2099-01-01T00:00:00Z")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LEASE_OVERLAP"));
    }
}
