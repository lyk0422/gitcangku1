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
 * 容量账本与闭环转配 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由）：
 * 覆盖配置/激活/预览/转配/证据主流程与 400/404/409/422 失败分支。
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
        jdbc.update("DELETE FROM capacity_transfer_route");
        jdbc.update("DELETE FROM capacity_transfer_item");
        jdbc.update("DELETE FROM capacity_transfer");
        jdbc.update("DELETE FROM route_occupancy");
        jdbc.update("DELETE FROM capacity_config");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private void createRouteAndReview(String routeId, String pointsJson) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"req-route-%s",
                                 "points":%s}""".formatted(routeId, routeId, pointsJson)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-%s"}""".formatted(routeId, routeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));
    }

    private void activate(String routeId) throws Exception {
        mockMvc.perform(post("/api/airspace/capacity/routes/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","expectedVersion":1,"departureTime":0,
                                 "requestId":"req-act-%s"}""".formatted(routeId, routeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bucketCount").value(2));
    }

    @Test
    @DisplayName("主流程：配置容量、激活航线、预览并激活闭环转配、查询冻结证据")
    void closedLoopTransferOverHttp() throws Exception {
        // 配置两个桶容量上限 1
        mockMvc.perform(post("/api/airspace/capacity/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellId":"C0_0","bucketStart":0,"maxFlights":1,
                                 "requestId":"req-cfg-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.maxFlights").value(1));
        mockMvc.perform(post("/api/airspace/capacity/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellId":"C1_0","bucketStart":0,"maxFlights":1,
                                 "requestId":"req-cfg-2"}"""))
                .andExpect(status().isCreated());

        // A: [C0_0, C0_1]，B: [C1_0, C1_1]
        createRouteAndReview("A", "[{\"x\":100,\"y\":100},{\"x\":100,\"y\":1100}]");
        createRouteAndReview("B", "[{\"x\":1100,\"y\":100},{\"x\":1100,\"y\":1100}]");
        activate("A");
        activate("B");

        // 预览：闭环互换在完整后态下合法
        String previewBody = """
                {"items":[
                  {"routeId":"A","expectedVersion":1,
                   "source":{"cellId":"C0_0","bucketStart":0},
                   "target":{"cellId":"C1_0","bucketStart":0}},
                  {"routeId":"B","expectedVersion":1,
                   "source":{"cellId":"C1_0","bucketStart":0},
                   "target":{"cellId":"C0_0","bucketStart":0}}]}""";
        mockMvc.perform(post("/api/airspace/capacity/transfers/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(previewBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.buckets[0].cellId").value("C0_0"))
                .andExpect(jsonPath("$.buckets[0].afterCount").value(1))
                .andExpect(jsonPath("$.buckets[0].margin").value(0));

        // 激活转配 → 201，两航线版本增为 2
        mockMvc.perform(post("/api/airspace/capacity/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-http-1","requestId":"req-transfer-1",
                                 "items":[
                                  {"routeId":"A","expectedVersion":1,
                                   "source":{"cellId":"C0_0","bucketStart":0},
                                   "target":{"cellId":"C1_0","bucketStart":0}},
                                  {"routeId":"B","expectedVersion":1,
                                   "source":{"cellId":"C1_0","bucketStart":0},
                                   "target":{"cellId":"C0_0","bucketStart":0}}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.transferKey").value("tk-http-1"))
                .andExpect(jsonPath("$.data.routes[0].newVersion").value(2));

        // 同键同参（项换序）→ 重放
        mockMvc.perform(post("/api/airspace/capacity/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-http-1","requestId":"req-transfer-1",
                                 "items":[
                                  {"routeId":"B","expectedVersion":1,
                                   "source":{"cellId":"C1_0","bucketStart":0},
                                   "target":{"cellId":"C0_0","bucketStart":0}},
                                  {"routeId":"A","expectedVersion":1,
                                   "source":{"cellId":"C0_0","bucketStart":0},
                                   "target":{"cellId":"C1_0","bucketStart":0}}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.transferKey").value("tk-http-1"));

        // 证据查询：冻结前后路径与桶余量，稳定排序
        mockMvc.perform(get("/api/airspace/capacity/transfers/tk-http-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].routeId").value("A"))
                .andExpect(jsonPath("$.routes[0].oldVersion").value(1))
                .andExpect(jsonPath("$.routes[0].newVersion").value(2))
                .andExpect(jsonPath("$.routes[0].afterPath[0].cellId").value("C1_0"))
                .andExpect(jsonPath("$.buckets[0].cellId").value("C0_0"))
                .andExpect(jsonPath("$.buckets[1].cellId").value("C1_0"));
    }

    @Test
    @DisplayName("失败分支：400 校验、404 证据、409 版本/键冲突、422 容量超限")
    void failureBranchesOverHttp() throws Exception {
        // 未对齐时间桶 → 400
        mockMvc.perform(post("/api/airspace/capacity/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellId":"C0_0","bucketStart":1000,"maxFlights":1,
                                 "requestId":"req-bad-1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNALIGNED_BUCKET"));

        // 证据不存在 → 404
        mockMvc.perform(get("/api/airspace/capacity/transfers/tk-ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));

        createRouteAndReview("A", "[{\"x\":100,\"y\":100},{\"x\":100,\"y\":1100}]");
        createRouteAndReview("B", "[{\"x\":1100,\"y\":100},{\"x\":1100,\"y\":1100}]");
        activate("A");
        activate("B");

        // 版本不匹配 → 409
        mockMvc.perform(post("/api/airspace/capacity/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-bad-v","requestId":"req-bad-v",
                                 "items":[
                                  {"routeId":"A","expectedVersion":9,
                                   "source":{"cellId":"C0_0","bucketStart":0},
                                   "target":{"cellId":"C1_0","bucketStart":0}},
                                  {"routeId":"B","expectedVersion":1,
                                   "source":{"cellId":"C1_0","bucketStart":0},
                                   "target":{"cellId":"C0_0","bucketStart":0}}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 容量超限（(C1_0,0) 上限 0）→ 422，且整体回滚
        mockMvc.perform(post("/api/airspace/capacity/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellId":"C1_0","bucketStart":0,"maxFlights":0,
                                 "requestId":"req-cfg-zero"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/capacity/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferKey":"tk-bad-cap","requestId":"req-bad-cap",
                                 "items":[
                                  {"routeId":"A","expectedVersion":1,
                                   "source":{"cellId":"C0_0","bucketStart":0},
                                   "target":{"cellId":"C1_0","bucketStart":0}},
                                  {"routeId":"B","expectedVersion":1,
                                   "source":{"cellId":"C1_0","bucketStart":0},
                                   "target":{"cellId":"C0_0","bucketStart":0}}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));
        // 回滚：无转配记录、占用与版本不变
        mockMvc.perform(get("/api/airspace/capacity/transfers/tk-bad-cap"))
                .andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'A'", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = 'req-bad-cap'",
                Integer.class));
    }
}
