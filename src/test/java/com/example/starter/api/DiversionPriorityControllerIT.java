package com.example.starter.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 紧急备降优先级与时空容量抢占的 HTTP 端到端测试：
 * 验证新端点 JSON 契约、422 结构化 details、查询接口与统一错误体。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("紧急备降优先级 HTTP API 端到端")
class DiversionPriorityControllerIT {

    private static final long WIN_START = 1_000_000_000_000L;
    private static final long WIN_END = WIN_START + 30 * 60_000L;
    private static final String BUCKET_KEY = "1:2:" + (WIN_START / 60_000L) + ":" + (WIN_END / 60_000L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM preemption");
        jdbc.update("DELETE FROM bucket_occupancy");
        jdbc.update("DELETE FROM capacity_bucket");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private void createRoute(String routeId) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"req-route-%s",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""
                                .formatted(routeId, routeId)))
                .andExpect(status().isCreated());
    }

    private void createBucket(int capacity) throws Exception {
        mockMvc.perform(post("/api/airspace/capacity-buckets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellX":1,"cellY":2,"windowStart":%d,"windowEnd":%d,
                                 "capacity":%d,"requestId":"req-bucket-1"}"""
                                .formatted(WIN_START, WIN_END, capacity)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bucketKey").value(BUCKET_KEY));
    }

    private void review(String routeId, String priority, String eventNo, String requestId,
                        int expectStatus) throws Exception {
        String eventPart = eventNo == null ? "" : "\"eventNo\":\"" + eventNo + "\",";
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":0,
                                 "priority":"%s",%s
                                 "cellX":1,"cellY":2,"windowStart":%d,"windowEnd":%d,
                                 "requestId":"%s"}"""
                                .formatted(routeId, priority, eventPart, WIN_START, WIN_END, requestId)))
                .andExpect(status().is(expectStatus));
    }

    @Test
    @DisplayName("主流程：建桶→NORMAL 批准→起飞→EMERGENCY 422 列出不可抢占→查询接口")
    void emergencyPreemptionFlowOverHttp() throws Exception {
        createRoute("na");
        createRoute("em");
        createBucket(1);

        // NORMAL 批准占用容量
        review("na", "NORMAL", null, "req-review-na", 201);

        // 起飞登记
        mockMvc.perform(post("/api/airspace/routes/depart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"na\",\"requestId\":\"req-depart-na\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEPARTED"));

        // 容量桶查询：used=1 remaining=0
        mockMvc.perform(get("/api/airspace/capacity-buckets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bucketKey").value(BUCKET_KEY))
                .andExpect(jsonPath("$[0].used").value(1))
                .andExpect(jsonPath("$[0].remaining").value(0));

        // EMERGENCY 无法抢占已起飞：422 且 details 列出不可抢占航线
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"em","routeVersion":1,"airspaceVersion":0,
                                 "priority":"EMERGENCY","eventNo":"EV-900",
                                 "cellX":1,"cellY":2,"windowStart":%d,"windowEnd":%d,
                                 "requestId":"req-review-em"}"""
                                .formatted(WIN_START, WIN_END)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAPACITY_INSUFFICIENT"))
                .andExpect(jsonPath("$.details.nonPreemptableRouteIds[0]").value("na"));

        // EMERGENCY 缺事件编号 → 400
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"em","routeVersion":1,"airspaceVersion":0,
                                 "priority":"EMERGENCY",
                                 "cellX":1,"cellY":2,"windowStart":%d,"windowEnd":%d,
                                 "requestId":"req-review-em-noevent"}"""
                                .formatted(WIN_START, WIN_END)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_NO_REQUIRED"));

        // 空查询结果
        mockMvc.perform(get("/api/airspace/preemptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/airspace/routes/displaced"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("抢占主流程：EMERGENCY 置换 NORMAL，快照与被置换航线可查询")
    void preemptionVisibleOverHttp() throws Exception {
        createRoute("victim");
        createRoute("em");
        createBucket(1);
        review("victim", "NORMAL", null, "req-review-victim", 201);
        review("em", "EMERGENCY", "EV-901", "req-review-em", 201);

        // 被置换航线查询
        mockMvc.perform(get("/api/airspace/routes/displaced"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].routeId").value("victim"))
                .andExpect(jsonPath("$[0].status").value("DISPLACED"));

        // 抢占记录查询（含过滤）
        mockMvc.perform(get("/api/airspace/preemptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].routeId").value("victim"))
                .andExpect(jsonPath("$[0].emergencyRouteId").value("em"))
                .andExpect(jsonPath("$[0].emergencyEventNo").value("EV-901"))
                .andExpect(jsonPath("$[0].state").value("PENDING"))
                .andExpect(jsonPath("$[0].bucketKey").value(BUCKET_KEY));
        mockMvc.perform(get("/api/airspace/preemptions").param("state", "RESUBMITTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/airspace/preemptions").param("state", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PREEMPTION_STATE"));

        // 被置换航线重新提交审查（无时空段）→ 记录转 RESUBMITTED
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"victim","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-victim-2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));
        mockMvc.perform(get("/api/airspace/preemptions").param("routeId", "victim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("RESUBMITTED"));
    }
}
