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
 * 跨事件互助征调 HTTP 层测试（真实 H2）：验证路由、请求头、200/201/404/409/422 语义，
 * 以及题干主流程 A 有 R 5 件，征调 3 给 B 后 A 剩 2、B 租约 3，还 2 后待还 1、再还 2 返回 422。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MutualAidApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM resource_aid_returns");
        jdbc.update("DELETE FROM resource_aid_leases");
        jdbc.update("DELETE FROM incident_resource_inventories");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incidents");
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private void report(String key) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + key
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void aidMainFlowOverHttp() throws Exception {
        report("A");
        report("B");

        // A 登记资源 R 5 件
        mvc.perform(post("/api/incidents/A/resources")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceCode\":\"R\",\"quantity\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availableQuantity").value(5));

        // 征调 3 件给 B：A 剩 2，B 租约 3、待还 3
        mvc.perform(post("/api/incidents/aid/requests")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-HTTP-1\",\"sourceIncidentKey\":\"A\","
                                + "\"targetIncidentKey\":\"B\",\"resourceCode\":\"R\",\"quantity\":3,"
                                + "\"exclusive\":false,\"expiresAt\":\"2026-10-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lease.requestId").value("REQ-HTTP-1"))
                .andExpect(jsonPath("$.lease.sourceIncidentKey").value("A"))
                .andExpect(jsonPath("$.lease.targetIncidentKey").value("B"))
                .andExpect(jsonPath("$.lease.quantity").value(3))
                .andExpect(jsonPath("$.lease.returnedQuantity").value(0))
                .andExpect(jsonPath("$.lease.outstandingQuantity").value(3))
                .andExpect(jsonPath("$.lease.status").value("ACTIVE"))
                .andExpect(jsonPath("$.sourceInventory.availableQuantity").value(2))
                .andExpect(jsonPath("$.returns").isArray());

        // 归还 2：待还 1，A 回到 4，一条流水
        mvc.perform(post("/api/incidents/aid/requests/REQ-HTTP-1/returns")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + uuid() + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lease.returnedQuantity").value(2))
                .andExpect(jsonPath("$.lease.outstandingQuantity").value(1))
                .andExpect(jsonPath("$.sourceInventory.availableQuantity").value(4))
                .andExpect(jsonPath("$.returns.length()").value(1));

        // 再还 2 超过待还 1：422
        mvc.perform(post("/api/incidents/aid/requests/REQ-HTTP-1/returns")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + uuid() + "\",\"quantity\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));

        // 查询关联账务：租约仍待还 1、A 仍 4、仅一条流水
        mvc.perform(get("/api/incidents/aid/requests/REQ-HTTP-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lease.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lease.outstandingQuantity").value(1))
                .andExpect(jsonPath("$.sourceInventory.availableQuantity").value(4))
                .andExpect(jsonPath("$.returns.length()").value(1));

        // 事件维度可查到关联租约（含来源与目标）
        mvc.perform(get("/api/incidents/A/aid-leases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value("REQ-HTTP-1"))
                .andExpect(jsonPath("$[0].sourceIncidentKey").value("A"))
                .andExpect(jsonPath("$[0].targetIncidentKey").value("B"));
        mvc.perform(get("/api/incidents/B/aid-leases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value("REQ-HTTP-1"));
    }

    @Test
    void insufficientSource_overHttp422() throws Exception {
        report("A");
        report("B");
        mvc.perform(post("/api/incidents/A/resources")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceCode\":\"R\",\"quantity\":1}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/incidents/aid/requests")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-HTTP-2\",\"sourceIncidentKey\":\"A\","
                                + "\"targetIncidentKey\":\"B\",\"resourceCode\":\"R\",\"quantity\":2,"
                                + "\"exclusive\":false,\"expiresAt\":\"2026-10-01T00:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));

        // 来源未被扣减
        mvc.perform(get("/api/incidents/A/resources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableQuantity").value(1));
    }

    @Test
    void sameRequestIdChangedTarget_overHttp409() throws Exception {
        report("A");
        report("B");
        report("C");
        mvc.perform(post("/api/incidents/A/resources")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceCode\":\"R\",\"quantity\":5}"))
                .andExpect(status().isCreated());

        String body = "{\"requestId\":\"REQ-HTTP-3\",\"sourceIncidentKey\":\"A\","
                + "\"targetIncidentKey\":\"B\",\"resourceCode\":\"R\",\"quantity\":3,"
                + "\"exclusive\":false,\"expiresAt\":\"2026-10-01T00:00:00Z\"}";
        mvc.perform(post("/api/incidents/aid/requests")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // 同键改目标事件 C：409
        mvc.perform(post("/api/incidents/aid/requests")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-HTTP-3\",\"sourceIncidentKey\":\"A\","
                                + "\"targetIncidentKey\":\"C\",\"resourceCode\":\"R\",\"quantity\":3,"
                                + "\"exclusive\":false,\"expiresAt\":\"2026-10-01T00:00:00Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void missingActorHeader_overHttp400_andMissingLease404() throws Exception {
        report("A");
        report("B");
        mvc.perform(post("/api/incidents/aid/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-HTTP-4\",\"sourceIncidentKey\":\"A\","
                                + "\"targetIncidentKey\":\"B\",\"resourceCode\":\"R\",\"quantity\":1,"
                                + "\"exclusive\":false,\"expiresAt\":\"2026-10-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/incidents/aid/requests/NO-SUCH"))
                .andExpect(status().isNotFound());
    }
}
