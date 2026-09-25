package com.example.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 紧急备降优先级与时空容量抢占 API 端到端测试（H2 + 真实 MVC、事务与校验）：
 * 覆盖 NORMAL 批准/422、EMERGENCY 抢占、起飞不可抢占、快照冻结与查询接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("紧急备降优先级 HTTP API 端到端")
class DiversionPriorityControllerIT {

    /** 与 Service 测试一致的基准时刻（落在 10 分钟桶内）。 */
    private static final long T0 = 1_700_000_040_000L;
    private static final long BUCKET_MS = 10L * 60L * 1000L;
    private static final long TIME_BUCKET = Math.floorDiv(T0, BUCKET_MS);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM preemption_item");
        jdbc.update("DELETE FROM preemption");
        jdbc.update("DELETE FROM clearance_bucket");
        jdbc.update("DELETE FROM clearance");
        jdbc.update("DELETE FROM cell_capacity");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private String routeBody(String routeId, String requestId) {
        return """
                {"routeId":"%s","requestId":"%s",
                 "points":[{"x":0,"y":0},{"x":100,"y":0}]}""".formatted(routeId, requestId);
    }

    private String priorityBody(String routeId, String priority, String eventNo,
                                long atMillis, int x, int y, String requestId) {
        String eventField = eventNo == null ? "null" : "\"" + eventNo + "\"";
        return """
                {"routeId":"%s","routeVersion":1,"airspaceVersion":0,
                 "priority":"%s","eventNo":%s,
                 "segments":[{"atMillis":%d,"x":%d,"y":%d}],
                 "requestId":"%s"}""".formatted(routeId, priority, eventField, atMillis, x, y,
                requestId);
    }

    private String readDataField(MvcResult result, String field) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.path("data").path(field).asText();
    }

    @Test
    @DisplayName("NORMAL 批准占桶；第二架 NORMAL 422 且响应列出占用航线")
    void normalApprovalAndCapacity422OverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("hn1", "req-h-route-1")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(priorityBody("hn1", "NORMAL", null, T0, 0, 0, "req-h-n1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.priority").value("NORMAL"))
                .andExpect(jsonPath("$.data.eventNo").isEmpty())
                .andExpect(jsonPath("$.data.preemptionId").isEmpty());

        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("hn2", "req-h-route-2")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(priorityBody("hn2", "NORMAL", null, T0, 0, 0, "req-h-n2")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAPACITY_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.blockingRoutes[0].routeId").value("hn1"))
                .andExpect(jsonPath("$.blockingRoutes[0].status").value("APPROVED"));

        mockMvc.perform(get("/api/airspace/capacity/buckets")
                        .param("cellX", "0").param("cellY", "0")
                        .param("timeBucket", String.valueOf(TIME_BUCKET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(1))
                .andExpect(jsonPath("$.occupied").value(1))
                .andExpect(jsonPath("$.occupants[0].routeId").value("hn1"));
    }

    @Test
    @DisplayName("EMERGENCY 缺事件号 400；抢占 NORMAL 后快照与被置换航线可查询")
    void emergencyValidationPreemptionAndQueriesOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("he1", "req-he-route")))
                .andExpect(status().isCreated());
        // 缺事件号
        mockMvc.perform(post("/api/airspace/reviews/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(priorityBody("he1", "EMERGENCY", null, T0, 0, 0, "req-he-bad")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_NO_REQUIRED"));

        // 先批准一条 NORMAL
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("hn", "req-hn-route")))
                .andExpect(status().isCreated());
        MvcResult normalResult = mockMvc.perform(post("/api/airspace/reviews/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(priorityBody("hn", "NORMAL", null, T0, 0, 0, "req-hn")))
                .andExpect(status().isCreated()).andReturn();
        String normalClearanceId = readDataField(normalResult, "clearanceId");

        // 紧急抢占
        MvcResult emergencyResult = mockMvc.perform(post("/api/airspace/reviews/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(priorityBody("he1", "EMERGENCY", "EVT-WEB-1", T0, 0, 0,
                                "req-he1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.preemptionId").isNotEmpty())
                .andExpect(jsonPath("$.data.displaced[0].routeId").value("hn"))
                .andExpect(jsonPath("$.data.displaced[0].status").value("PENDING"))
                .andReturn();
        String preemptionId = readDataField(emergencyResult, "preemptionId");

        // 被置换批件查询
        mockMvc.perform(get("/api/airspace/clearances/" + normalClearanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPLACED"));
        // 抢占快照查询：冻结字段
        mockMvc.perform(get("/api/airspace/preemptions/" + preemptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventNo").value("EVT-WEB-1"))
                .andExpect(jsonPath("$.emergencyRouteId").value("he1"))
                .andExpect(jsonPath("$.displacedRouteIds[0]").value("hn"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));
        // 被置换航线查询
        mockMvc.perform(get("/api/airspace/routes/hn/displaced"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("起飞后紧急抢占 422 列 DEPARTED；起飞接口幂等重放")
    void departedRouteCannotBePreemptedOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("hd1", "req-hd-route-1")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("hde", "req-hd-route-2")))
                .andExpect(status().isCreated());
        MvcResult normalResult = mockMvc.perform(post("/api/airspace/reviews/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(priorityBody("hd1", "NORMAL", null, T0, 0, 0, "req-hd-n")))
                .andExpect(status().isCreated()).andReturn();
        String clearanceId = readDataField(normalResult, "clearanceId");

        String departureBody = """
                {"clearanceId":"%s","requestId":"req-hd-dep"}""".formatted(clearanceId);
        mockMvc.perform(post("/api/airspace/departures")
                        .contentType(MediaType.APPLICATION_JSON).content(departureBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DEPARTED"));
        // 同键重放
        mockMvc.perform(post("/api/airspace/departures")
                        .contentType(MediaType.APPLICATION_JSON).content(departureBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));

        // 紧急航线不可抢占已起飞 NORMAL
        mockMvc.perform(post("/api/airspace/reviews/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(priorityBody("hde", "EMERGENCY", "EVT-WEB-2", T0, 0, 0,
                                "req-hd-e")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAPACITY_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.blockingRoutes[0].routeId").value("hd1"))
                .andExpect(jsonPath("$.blockingRoutes[0].reason").value("DEPARTED"))
                .andExpect(jsonPath("$.blockingRoutes[0].status").value("DEPARTED"));
    }
}
