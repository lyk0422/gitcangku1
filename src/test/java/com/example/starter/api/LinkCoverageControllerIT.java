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
 * 通信覆盖审查 HTTP API 端到端（H2 + 真实 Spring MVC 路由、校验与统一异常处理）：
 * 题干合成样例主流程、LINK_BLOCKED、400 失败分支与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("通信覆盖审查 HTTP API 端到端")
class LinkCoverageControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_segment_coverage");
        jdbc.update("DELETE FROM route_segment");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private static String routeBody(String routeId, String requestId,
                                    int cov1From, int cov1To, int cov2From, int cov2To) {
        return """
                {"routeId":"%s","requestId":"%s","operator":"tester",
                 "points":[{"x":0,"y":0},{"x":0,"y":10},{"x":0,"y":20}],
                 "segments":[
                   {"durationMs":10000,"coverage":[{"from":%d,"to":%d}]},
                   {"durationMs":10000,"coverage":[{"from":%d,"to":%d}]}]}"""
                .formatted(routeId, requestId, cov1From, cov1To, cov2From, cov2To);
    }

    @Test
    @DisplayName("合成样例：80%/70% 相接失联正好 5 秒允许，69% 则 5.1 秒 LINK_BLOCKED")
    void synthesisScenarioOverHttp() throws Exception {
        // 第一段仅覆盖前 80%，第二段仅覆盖后 70%：相接失联 2+3=5 秒，正好允许
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("lr1", "req-lr1", 0, 8000, 3000, 10000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"lr1","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-lr1-review"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.linkUnion[0].intervals[0].to").value(8000))
                .andExpect(jsonPath("$.data.linkGaps[0].fromSegment").value(0))
                .andExpect(jsonPath("$.data.linkGaps[0].toSegment").value(1))
                .andExpect(jsonPath("$.data.linkGaps[0].lossMillis").value("5000"));

        // 第二段改为仅覆盖后 69%：相接失联 2+3.1=5.1 秒 → LINK_BLOCKED
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("lr2", "req-lr2", 0, 8000, 3100, 10000)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"lr2","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-lr2-review"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("LINK_BLOCKED"))
                .andExpect(jsonPath("$.data.linkGaps[0].lossMillis").value("5100"));
    }

    @Test
    @DisplayName("400 分支：区间反向、越界、缺操作者；失败不占幂等键")
    void invalidProfileOverHttp() throws Exception {
        // 覆盖区间反向 → 400
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"lb1","requestId":"req-lb1","operator":"tester",
                                 "points":[{"x":0,"y":0},{"x":0,"y":10}],
                                 "segments":[{"durationMs":1000,
                                   "coverage":[{"from":5000,"to":4000}]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COVERAGE_INTERVAL_REVERSED"));

        // 覆盖越界 → 400
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"lb2","requestId":"req-lb2","operator":"tester",
                                 "points":[{"x":0,"y":0},{"x":0,"y":10}],
                                 "segments":[{"durationMs":1000,
                                   "coverage":[{"from":0,"to":10001}]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COVERAGE_INTERVAL_OUT_OF_RANGE"));

        // 携带通信档案但缺操作者 → 400，且失败不占键
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"lb3","requestId":"req-lb3",
                                 "points":[{"x":0,"y":0},{"x":0,"y":10}],
                                 "segments":[{"durationMs":1000,
                                   "coverage":[{"from":0,"to":10000}]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPERATOR_REQUIRED"));
        // 同键补上操作者后可成功
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"lb3","requestId":"req-lb3","operator":"tester",
                                 "points":[{"x":0,"y":0},{"x":0,"y":10}],
                                 "segments":[{"durationMs":1000,
                                   "coverage":[{"from":0,"to":10000}]}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    @DisplayName("幂等：同键同参重放完整响应，异参 409")
    void idempotentReplayWithProfileOverHttp() throws Exception {
        String body = routeBody("li1", "req-li1", 0, 8000, 3100, 10000);
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        // 同键同参重放
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键异参（覆盖不同）→ 409
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("li1", "req-li1", 0, 8000, 3000, 10000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
    }
}
