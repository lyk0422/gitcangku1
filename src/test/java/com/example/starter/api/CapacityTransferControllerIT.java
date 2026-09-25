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
 * 容量转配 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由与统一异常处理）：
 * 覆盖配置/激活/预览/转配/证据查询主流程与 400/404/409/422 状态映射。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("容量转配 HTTP API 端到端")
class CapacityTransferControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM capacity_transfer_bucket");
        jdbc.update("DELETE FROM capacity_transfer_item");
        jdbc.update("DELETE FROM capacity_transfer");
        jdbc.update("DELETE FROM capacity_occupancy");
        jdbc.update("DELETE FROM route_activation");
        jdbc.update("DELETE FROM capacity_config");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private void createReviewActivate(String routeId, long departure, double speed,
                                      String tag) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"req-route-%s",
                                 "points":[{"x":100,"y":500},{"x":3100,"y":500}]}"""
                                .formatted(routeId, tag)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-%s"}""".formatted(routeId, tag)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));
        mockMvc.perform(post("/api/airspace/routes/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","expectedVersion":1,"departureTime":%d,
                                 "speedMps":%s,"requestId":"req-act-%s"}"""
                                .formatted(routeId, departure, speed, tag)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1));
    }

    private void config(String cellId, long bucketStart, int maxFlights, String tag)
            throws Exception {
        mockMvc.perform(post("/api/airspace/capacity-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellId":"%s","bucketStart":%d,"maxFlights":%d,
                                 "requestId":"req-cfg-%s"}"""
                                .formatted(cellId, bucketStart, maxFlights, tag)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("主流程：配置/激活/预览/闭环转配/证据查询，幂等重放标记正确")
    void closedLoopOverHttp() throws Exception {
        createReviewActivate("A", 0L, 1.0d, "a1");
        createReviewActivate("B", 0L, 2.0d, "b1");
        createReviewActivate("C", 900L, 1.0d, "c1");
        config("1:0", 900L, 1, "1");
        config("2:0", 900L, 1, "2");
        config("1:0", 1800L, 1, "3");

        String items = """
                [{"routeId":"A","expectedVersion":1,
                  "sourceBucket":{"cellId":"1:0","bucketStart":900},
                  "targetBucket":{"cellId":"2:0","bucketStart":900}},
                 {"routeId":"B","expectedVersion":1,
                  "sourceBucket":{"cellId":"2:0","bucketStart":900},
                  "targetBucket":{"cellId":"1:0","bucketStart":1800}},
                 {"routeId":"C","expectedVersion":1,
                  "sourceBucket":{"cellId":"1:0","bucketStart":1800},
                  "targetBucket":{"cellId":"1:0","bucketStart":900}}]""";

        // 预览：只读，可激活
        mockMvc.perform(post("/api/airspace/capacity-transfers/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":" + items + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicable").value(true))
                .andExpect(jsonPath("$.violations").isEmpty())
                .andExpect(jsonPath("$.buckets.length()").value(3));

        // 激活 → 201，逐航线增版
        mockMvc.perform(post("/api/airspace/capacity-transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-http","requestId":"req-tk-1",
                                 "items":%s}""".formatted(items)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.transferKey").value("tk-http"))
                .andExpect(jsonPath("$.data.items[0].routeId").value("A"))
                .andExpect(jsonPath("$.data.items[0].newVersion").value(2));

        // 同键同参重放 → replayed=true
        mockMvc.perform(post("/api/airspace/capacity-transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-http","requestId":"req-tk-1",
                                 "items":%s}""".formatted(items)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));

        // 证据查询 → 200，稳定排序
        mockMvc.perform(get("/api/airspace/capacity-transfers/tk-http"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].routeId").value("A"))
                .andExpect(jsonPath("$.items[2].routeId").value("C"))
                .andExpect(jsonPath("$.buckets[0].cellId").value("1:0"))
                .andExpect(jsonPath("$.buckets[0].bucketStart").value(900));
    }

    @Test
    @DisplayName("失败分支：422 容量超限、409 版本冲突、404 证据不存在、400 参数校验")
    void failureStatusMapping() throws Exception {
        createReviewActivate("A", 0L, 1.0d, "a2");
        createReviewActivate("B", 0L, 2.0d, "b2");
        // 目标桶未配置 → 422 CAPACITY_NOT_CONFIGURED
        mockMvc.perform(post("/api/airspace/capacity-transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-422","requestId":"req-tk-422",
                                 "items":[
                                  {"routeId":"A","expectedVersion":1,
                                   "sourceBucket":{"cellId":"1:0","bucketStart":900},
                                   "targetBucket":{"cellId":"2:0","bucketStart":900}},
                                  {"routeId":"B","expectedVersion":1,
                                   "sourceBucket":{"cellId":"2:0","bucketStart":900},
                                   "targetBucket":{"cellId":"1:0","bucketStart":1800}}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAPACITY_NOT_CONFIGURED"));

        // 版本冲突 → 409
        config("2:0", 900L, 1, "9");
        mockMvc.perform(post("/api/airspace/capacity-transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-409","requestId":"req-tk-409",
                                 "items":[
                                  {"routeId":"A","expectedVersion":7,
                                   "sourceBucket":{"cellId":"1:0","bucketStart":900},
                                   "targetBucket":{"cellId":"2:0","bucketStart":900}},
                                  {"routeId":"B","expectedVersion":1,
                                   "sourceBucket":{"cellId":"2:0","bucketStart":900},
                                   "targetBucket":{"cellId":"1:0","bucketStart":1800}}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 证据不存在 → 404
        mockMvc.perform(get("/api/airspace/capacity-transfers/tk-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));

        // 项数不足 → 400（Bean Validation）
        mockMvc.perform(post("/api/airspace/capacity-transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-400","requestId":"req-tk-400",
                                 "items":[
                                  {"routeId":"A","expectedVersion":1,
                                   "sourceBucket":{"cellId":"1:0","bucketStart":900},
                                   "targetBucket":{"cellId":"2:0","bucketStart":900}}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
