package com.example.starter.api;

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

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 定时航线审查 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由、校验与统一异常处理）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("定时航线审查 HTTP API 端到端")
class TimedReviewControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM timed_review");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private void createRoute(String routeId, String requestId, String pointsJson) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\",\"requestId\":\"" + requestId
                                + "\",\"points\":" + pointsJson + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("主流程：100米/2秒 FEASIBLE，历史冻结，替换后当前 STALE")
    void fullTimedFlowOverHttp() throws Exception {
        createRoute("r1", "req-route-1", "[{\"x\":0,\"y\":0},{\"x\":100,\"y\":0}]");

        // 两点相距 100 米、间隔 2 秒：速度恰好 50 米/秒 → 201 FEASIBLE
        MvcResult feasible = mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","expectedRouteVersion":1,"airspaceVersion":0,
                                 "operator":"alice","requestId":"req-timed-1",
                                 "arrivalTimes":["2026-10-04T12:00:00Z","2026-10-04T12:00:02Z"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.conclusion").value("FEASIBLE"))
                .andExpect(jsonPath("$.data.operator").value("alice"))
                .andExpect(jsonPath("$.data.current").value(true))
                .andExpect(jsonPath("$.data.arrivalTimes[1]").value("2026-10-04T12:00:02Z"))
                .andReturn();
        String reviewId = objectMapper.readTree(feasible.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 同键同参重放：返回首次完整响应
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","expectedRouteVersion":1,"airspaceVersion":0,
                                 "operator":"alice","requestId":"req-timed-1",
                                 "arrivalTimes":["2026-10-04T12:00:00Z","2026-10-04T12:00:02Z"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.reviewId").value(reviewId));

        // 历史查询：冻结坐标、时刻与原结论
        mockMvc.perform(get("/api/airspace/timed-reviews/" + reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("FEASIBLE"))
                .andExpect(jsonPath("$.routeVersion").value(1))
                .andExpect(jsonPath("$.airspaceVersion").value(0))
                .andExpect(jsonPath("$.pointsSnapshot[1].x").value(100));

        // 当前查询：FEASIBLE 可用
        mockMvc.perform(get("/api/airspace/routes/r1/current-timed-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("FEASIBLE"))
                .andExpect(jsonPath("$.current").value(true));

        // 替换航线后：当前 STALE，历史不变；定时计划不得重绑新坐标
        mockMvc.perform(post("/api/airspace/routes/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","expectedVersion":1,"requestId":"req-replace-1",
                                 "points":[{"x":0,"y":50},{"x":100,"y":50}]}"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/airspace/routes/r1/current-timed-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"))
                .andExpect(jsonPath("$.current").value(false));
        mockMvc.perform(get("/api/airspace/timed-reviews/" + reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("FEASIBLE"))
                .andExpect(jsonPath("$.routeVersion").value(1));
    }

    @Test
    @DisplayName("100.001米/2秒 超速 422：返回航段号与未舍入速度，不保存")
    void overspeedReturns422WithSegmentAndUnroundedSpeed() throws Exception {
        createRoute("r2", "req-route-2", "[{\"x\":0,\"y\":0},{\"x\":100.001,\"y\":0}]");
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","expectedRouteVersion":1,"airspaceVersion":0,
                                 "operator":"bob","requestId":"req-timed-2",
                                 "arrivalTimes":["2026-10-04T12:00:00Z","2026-10-04T12:00:02Z"]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEGMENT_SPEED_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.segmentIndex").value(1))
                .andExpect(jsonPath("$.speedMetersPerSecond", greaterThan(50.0)));
        // 不保存任何审查结果
        mockMvc.perform(get("/api/airspace/routes/r2/current-timed-review"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("失败分支：点数不一致/时刻重复 400，版本不符 409，缺记录 404")
    void failureBranchesOverHttp() throws Exception {
        createRoute("r3", "req-route-3", "[{\"x\":0,\"y\":0},{\"x\":100,\"y\":0}]");

        // 点数不一致 → 400
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r3","expectedRouteVersion":1,"airspaceVersion":0,
                                 "operator":"bob","requestId":"req-timed-3a",
                                 "arrivalTimes":["2026-10-04T12:00:00Z","2026-10-04T12:00:02Z",
                                                 "2026-10-04T12:00:03Z"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIMED_POINT_COUNT_MISMATCH"));

        // 时刻重复 → 400
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r3","expectedRouteVersion":1,"airspaceVersion":0,
                                 "operator":"bob","requestId":"req-timed-3b",
                                 "arrivalTimes":["2026-10-04T12:00:00Z","2026-10-04T12:00:00Z"]}"""))
                .andExpect(status().isBadRequest());

        // expectedRouteVersion 不符 → 409
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r3","expectedRouteVersion":7,"airspaceVersion":0,
                                 "operator":"bob","requestId":"req-timed-3c",
                                 "arrivalTimes":["2026-10-04T12:00:00Z","2026-10-04T12:00:02Z"]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 历史/当前记录不存在 → 404
        mockMvc.perform(get("/api/airspace/timed-reviews/tr_missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/airspace/routes/r3/current-timed-review"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Bean 校验：超过 20 个时刻 400，非整秒 400")
    void beanValidationOverHttp() throws Exception {
        createRoute("r4", "req-route-4", "[{\"x\":0,\"y\":0},{\"x\":100,\"y\":0}]");
        // 21 个时刻（超过 20 航点上限）→ 400 VALIDATION_FAILED
        StringBuilder times = new StringBuilder("[");
        for (int i = 0; i <= 20; i++) {
            if (i > 0) {
                times.append(',');
            }
            times.append("\"2026-10-04T12:00:").append(String.format("%02d", i)).append("Z\"");
        }
        times.append(']');
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"r4\",\"expectedRouteVersion\":1,"
                                + "\"airspaceVersion\":0,\"operator\":\"bob\","
                                + "\"requestId\":\"req-timed-4a\",\"arrivalTimes\":" + times + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 非整秒（含毫秒）→ 400
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r4","expectedRouteVersion":1,"airspaceVersion":0,
                                 "operator":"bob","requestId":"req-timed-4b",
                                 "arrivalTimes":["2026-10-04T12:00:00.500Z",
                                                 "2026-10-04T12:00:02Z"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARRIVAL_TIME"));
    }
}