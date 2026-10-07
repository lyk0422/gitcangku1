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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 链路覆盖 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由与统一异常处理）：
 * 合成样例主流程、400 拒绝分支与十进制同值同参重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("链路覆盖 HTTP API 端到端")
class LinkCoverageControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_segment");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private static String routeBody(String routeId, String requestId, int secondStart) {
        return """
                {"routeId":"%s","requestId":"%s","operator":"tester",
                 "points":[{"x":0,"y":0},{"x":100,"y":0},{"x":200,"y":0}],
                 "segments":[
                   {"durationMs":10000,"coverage":[{"start":0,"end":8000}]},
                   {"durationMs":10000,"coverage":[{"start":%d,"end":10000}]}
                 ]}""".formatted(routeId, requestId, secondStart);
    }

    @Test
    @DisplayName("合成样例：前 80% + 后 70% 相接失联 5 秒允许；后 69% 则 5.1 秒阻断")
    void syntheticScenarioOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("http-ok", "req-http-route-1", 3000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"http-ok","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-http-review-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.maxContinuousLossMs").value("5000"))
                .andExpect(jsonPath("$.data.linkGaps[0].fromSegment").value(0))
                .andExpect(jsonPath("$.data.linkGaps[0].toSegment").value(1))
                .andExpect(jsonPath("$.data.linkGaps[0].lossMs").value("5000"))
                .andExpect(jsonPath("$.data.coverageUnion[0].merged[0].end").value(8000));

        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("http-bad", "req-http-route-2", 3100)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"http-bad","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-http-review-2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("LINK_BLOCKED"))
                .andExpect(jsonPath("$.data.maxContinuousLossMs").value("5100"));
    }

    @Test
    @DisplayName("反向/越界区间、缺操作者、段数不匹配均 400")
    void invalidCoverageRequestsRejected() throws Exception {
        // 反向区间
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"http-rev","requestId":"req-http-bad-1","operator":"tester",
                                 "points":[{"x":0,"y":0},{"x":100,"y":0}],
                                 "segments":[{"durationMs":10000,
                                   "coverage":[{"start":6000,"end":5000}]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COVERAGE_INTERVAL_INVALID"));

        // 越界区间
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"http-oob","requestId":"req-http-bad-2","operator":"tester",
                                 "points":[{"x":0,"y":0},{"x":100,"y":0}],
                                 "segments":[{"durationMs":10000,
                                   "coverage":[{"start":0,"end":10001}]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COVERAGE_INTERVAL_INVALID"));

        // 携带通信档案但缺操作者
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"http-noop","requestId":"req-http-bad-3",
                                 "points":[{"x":0,"y":0},{"x":100,"y":0}],
                                 "segments":[{"durationMs":10000,
                                   "coverage":[{"start":0,"end":10000}]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPERATOR_REQUIRED"));

        // 段数与航段数不匹配
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"http-cnt","requestId":"req-http-bad-4","operator":"tester",
                                 "points":[{"x":0,"y":0},{"x":100,"y":0}],
                                 "segments":[{"durationMs":10000,"coverage":[]},
                                   {"durationMs":10000,"coverage":[]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SEGMENT_COUNT_MISMATCH"));
    }

    @Test
    @DisplayName("十进制同值同参：10000.0 与 10000 视为同一请求重放")
    void decimalSameValueSameParams() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"http-dec","requestId":"req-http-dec","operator":"tester",
                                 "points":[{"x":0,"y":0},{"x":100,"y":0}],
                                 "segments":[{"durationMs":10000.0,
                                   "coverage":[{"start":0,"end":10000}]}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"http-dec","requestId":"req-http-dec","operator":"tester",
                                 "points":[{"x":0,"y":0},{"x":100,"y":0}],
                                 "segments":[{"durationMs":10000,
                                   "coverage":[{"start":0,"end":10000}]}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.version").value(1));
        // 重放不产生额外业务数据
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM route", Integer.class));
    }
}
