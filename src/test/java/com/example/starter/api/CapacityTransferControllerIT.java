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
 * 容量账本与闭环转配 HTTP API 端到端测试（H2 + 真实 Spring MVC）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("容量转配 HTTP API 端到端")
class CapacityTransferControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM transfer_bucket_evidence");
        jdbc.update("DELETE FROM transfer_route_evidence");
        jdbc.update("DELETE FROM capacity_transfer");
        jdbc.update("DELETE FROM route_occupancy_plan");
        jdbc.update("DELETE FROM capacity_config");
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
                                 "points":[{"x":0,"y":0},{"x":100,"y":100}]}""".formatted(
                                routeId, routeId)))
                .andExpect(status().isCreated());
    }

    private void approveClear(String routeId) throws Exception {
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-%s"}""".formatted(routeId, routeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));
    }

    @Test
    @DisplayName("主流程：配置容量、登记序列、预览可行、闭环激活、证据查询")
    void closedLoopOverHttp() throws Exception {
        for (String r : new String[]{"A", "B", "C"}) {
            createRoute(r);
            approveClear(r);
        }
        // 容量配置（201 + 幂等重放）
        String configBody = """
                {"cellX":1,"cellY":1,"bucketStart":0,"maxFlights":1,"requestId":"req-cap-x"}""";
        mockMvc.perform(post("/api/airspace/capacity/buckets")
                        .contentType(MediaType.APPLICATION_JSON).content(configBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.maxFlights").value(1));
        mockMvc.perform(post("/api/airspace/capacity/buckets")
                        .contentType(MediaType.APPLICATION_JSON).content(configBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        mockMvc.perform(post("/api/airspace/capacity/buckets").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellX":2,"cellY":1,"bucketStart":0,"maxFlights":1,
                                 "requestId":"req-cap-y"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/capacity/buckets").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellX":1,"cellY":2,"bucketStart":0,"maxFlights":1,
                                 "requestId":"req-cap-z"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/capacity/buckets").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellX":2,"cellY":2,"bucketStart":0,"maxFlights":2,
                                 "requestId":"req-cap-ab"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/capacity/buckets").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellX":0,"cellY":2,"bucketStart":0,"maxFlights":1,
                                 "requestId":"req-cap-c"}"""))
                .andExpect(status().isCreated());

        // 登记三条航线穿越序列
        registerPlanHttp("A", """
                [{"seq":0,"cellX":2,"cellY":2,"bucketStart":0},
                 {"seq":1,"cellX":1,"cellY":1,"bucketStart":0}]""", "req-plan-a");
        registerPlanHttp("B", """
                [{"seq":0,"cellX":2,"cellY":2,"bucketStart":0},
                 {"seq":1,"cellX":2,"cellY":1,"bucketStart":0}]""", "req-plan-b");
        registerPlanHttp("C", """
                [{"seq":0,"cellX":0,"cellY":2,"bucketStart":0},
                 {"seq":1,"cellX":1,"cellY":2,"bucketStart":0}]""", "req-plan-c");

        String transferBody = """
                {"transferKey":"tk-http-1","requestId":"req-trf-1","items":[
                  {"routeId":"A","routeVersion":1,"expectedVersion":1,
                   "source":{"cellX":1,"cellY":1,"bucketStart":0},
                   "target":{"cellX":2,"cellY":1,"bucketStart":0}},
                  {"routeId":"B","routeVersion":1,"expectedVersion":1,
                   "source":{"cellX":2,"cellY":1,"bucketStart":0},
                   "target":{"cellX":1,"cellY":2,"bucketStart":0}},
                  {"routeId":"C","routeVersion":1,"expectedVersion":1,
                   "source":{"cellX":1,"cellY":2,"bucketStart":0},
                   "target":{"cellX":1,"cellY":1,"bucketStart":0}}
                ]}""";

        // 预览可行、返回空域版本
        mockMvc.perform(post("/api/airspace/capacity/transfers/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.airspaceVersion").value(0))
                .andExpect(jsonPath("$.routes.length()").value(3));

        // 激活 201，逐航线 1→2
        MvcResult activated = mockMvc.perform(post("/api/airspace/capacity/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.routes.length()").value(3))
                .andReturn();
        JsonNode resp = objectMapper.readTree(activated.getResponse().getContentAsString());
        for (JsonNode route : resp.path("data").path("routes")) {
            org.junit.jupiter.api.Assertions.assertEquals(1, route.path("fromVersion").asInt());
            org.junit.jupiter.api.Assertions.assertEquals(2, route.path("toVersion").asInt());
        }

        // 证据查询稳定排序
        mockMvc.perform(get("/api/airspace/capacity/transfers/tk-http-1/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].routeId").value("A"))
                .andExpect(jsonPath("$.routes[2].routeId").value("C"))
                .andExpect(jsonPath("$.routes[0].reviewId").isNotEmpty())
                .andExpect(jsonPath("$.buckets.length()").value(5));

        // 不存在的转配单 → 404
        mockMvc.perform(get("/api/airspace/capacity/transfers/nope/evidence"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    @DisplayName("失败：项数不足 400；容量超限 422；transferKey 冲突 409")
    void failurePathsOverHttp() throws Exception {
        // A：锚点 (2,0)→(1,0)；B：锚点 (2,1)→(1,1)；两航线交换 (1,0)/(1,1)
        createRoute("A");
        createRoute("B");
        approveClear("A");
        approveClear("B");
        for (String bucket : new String[]{"2,0", "2,1", "1,0", "1,1"}) {
            String[] xy = bucket.split(",");
            mockMvc.perform(post("/api/airspace/capacity/buckets").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cellX":%s,"cellY":%s,"bucketStart":0,"maxFlights":1,
                                     "requestId":"req-c-%s-%s"}""".formatted(
                                    xy[0], xy[1], xy[0], xy[1])))
                    .andExpect(status().isCreated());
        }
        registerPlanHttp("A", """
                [{"seq":0,"cellX":2,"cellY":0,"bucketStart":0},
                 {"seq":1,"cellX":1,"cellY":0,"bucketStart":0}]""", "req-pa");
        registerPlanHttp("B", """
                [{"seq":0,"cellX":2,"cellY":1,"bucketStart":0},
                 {"seq":1,"cellX":1,"cellY":1,"bucketStart":0}]""", "req-pb");

        String body = """
                {"transferKey":"tk-bad","requestId":"req-bad","items":[
                  {"routeId":"A","routeVersion":1,"expectedVersion":1,
                   "source":{"cellX":1,"cellY":0,"bucketStart":0},
                   "target":{"cellX":1,"cellY":1,"bucketStart":0}},
                  {"routeId":"B","routeVersion":1,"expectedVersion":1,
                   "source":{"cellX":1,"cellY":1,"bucketStart":0},
                   "target":{"cellX":1,"cellY":0,"bucketStart":0}}
                ]}""";
        // 闭环互换后各桶仍恰好 1，激活成功
        mockMvc.perform(post("/api/airspace/capacity/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // transferKey 唯一：复用键 → 409
        mockMvc.perform(post("/api/airspace/capacity/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("req-bad", "req-bad-2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_KEY_EXISTS"));

        // 项数不足 2 → 400 校验失败
        mockMvc.perform(post("/api/airspace/capacity/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-one","requestId":"req-one","items":[
                                  {"routeId":"A","routeVersion":2,"expectedVersion":2,
                                   "source":{"cellX":1,"cellY":1,"bucketStart":0},
                                   "target":{"cellX":2,"cellY":1,"bucketStart":0}}
                                ]}"""))
                .andExpect(status().isBadRequest());

        // 新版本 2：A 把 (1,1) 搬到 B 的锚点 (2,1) → 该桶 A+B 共 2 > 上限 1；
        // B 自身 (1,0) 不动。整体 422 CAPACITY_EXCEEDED 且无部分转配
        mockMvc.perform(post("/api/airspace/capacity/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-over","requestId":"req-over","items":[
                                  {"routeId":"A","routeVersion":2,"expectedVersion":2,
                                   "source":{"cellX":1,"cellY":1,"bucketStart":0},
                                   "target":{"cellX":2,"cellY":1,"bucketStart":0}},
                                  {"routeId":"B","routeVersion":2,"expectedVersion":2,
                                   "source":{"cellX":1,"cellY":0,"bucketStart":0},
                                   "target":{"cellX":1,"cellY":0,"bucketStart":0}}
                                ]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));
        // 失败不留痕、版本不变
        mockMvc.perform(get("/api/airspace/capacity/transfers/tk-over/evidence"))
                .andExpect(status().isNotFound());
    }

    private void registerPlanHttp(String routeId, String itemsJson, String requestId)
            throws Exception {
        mockMvc.perform(post("/api/airspace/capacity/occupancy-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"items":%s,
                                 "requestId":"%s"}""".formatted(routeId, itemsJson, requestId)))
                .andExpect(status().isCreated());
    }
}
