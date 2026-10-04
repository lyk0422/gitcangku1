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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 定时航线可行性审查 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由、校验、
 * 统一异常处理与事务）：覆盖主流程、400/404/409/422 失败分支与幂等重放标记。
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

    private void createRoute(String routeId, String requestId) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"%s",
                                 "points":[{"x":0,"y":0},{"x":1000,"y":0}]}"""
                                .formatted(routeId, requestId)))
                .andExpect(status().isCreated());
    }

    private static String timedBody(String requestId, String operator, String routeId,
                                    int routeVersion, long airspaceVersion, String pointsJson) {
        return """
                {"requestId":"%s","operator":"%s","routeId":"%s",
                 "expectedRouteVersion":%d,"expectedAirspaceVersion":%d,
                 "points":%s}"""
                .formatted(requestId, operator, routeId, routeVersion, airspaceVersion, pointsJson);
    }

    private static final String TWO_POINTS_100M_2S = """
            [{"x":0,"y":0,"arrivalTime":"2026-10-04T08:00:00Z"},
             {"x":100,"y":0,"arrivalTime":"2026-10-04T08:00:02Z"}]""";

    @Test
    @DisplayName("主流程：100 米 / 2 秒边界放行 CLEAR，历史与当前查询语义正确")
    void mainFlowOverHttp() throws Exception {
        createRoute("r1", "req-route-1");

        MvcResult created = mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-1", "op-1", "r1", 1, 0, TWO_POINTS_100M_2S)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.operator").value("op-1"))
                .andExpect(jsonPath("$.data.routeVersion").value(1))
                .andExpect(jsonPath("$.data.airspaceVersion").value(0))
                .andExpect(jsonPath("$.data.current").value(true))
                .andExpect(jsonPath("$.data.points[1].x").value(100))
                .andExpect(jsonPath("$.data.points[1].arrivalTime")
                        .value("2026-10-04T08:00:02Z"))
                .andReturn();
        String reviewId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 历史查询：保留冻结的坐标、时刻与原结论
        mockMvc.perform(get("/api/airspace/timed-reviews/" + reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.points[0].arrivalTime").value("2026-10-04T08:00:00Z"));

        // 当前查询：版本匹配，结论可用
        mockMvc.perform(get("/api/airspace/routes/r1/current-timed-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.current").value(true));

        // 不存在的记录 404
        mockMvc.perform(get("/api/airspace/timed-reviews/tr_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("超速：100.001 米 / 2 秒返回 422、航段号与未舍入速度，不保存审查且不占键")
    void overspeedOverHttp() throws Exception {
        createRoute("r1", "req-route-1");
        String overspeed = """
                [{"x":0,"y":0,"arrivalTime":"2026-10-04T08:00:00Z"},
                 {"x":100.001,"y":0,"arrivalTime":"2026-10-04T08:00:02Z"}]""";
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-os", "op-1", "r1", 1, 0, overspeed)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEGMENT_SPEED_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.details.segmentIndex").value(1))
                .andExpect(jsonPath("$.details.speedMetersPerSecond").value(50.0005));

        // 未保存任何审查：当前查询 404
        mockMvc.perform(get("/api/airspace/routes/r1/current-timed-review"))
                .andExpect(status().isNotFound());

        // 失败不占键：同 requestId 修正参数后成功
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-os", "op-1", "r1", 1, 0, TWO_POINTS_100M_2S)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));
    }

    @Test
    @DisplayName("幂等：同键同参重放 replayed=true 且 reviewId 相同；同键异参 409")
    void idempotencyOverHttp() throws Exception {
        createRoute("r1", "req-route-1");
        String body = timedBody("tr-idem", "op-1", "r1", 1, 0, TWO_POINTS_100M_2S);
        MvcResult first = mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String reviewId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.reviewId").value(reviewId))
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));
        // 只落库一次
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM timed_review", Integer.class));

        // 同键异参（改操作者）→ 409
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-idem", "op-2", "r1", 1, 0, TWO_POINTS_100M_2S)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
    }

    @Test
    @DisplayName("失败分支：时刻重复/点数不一致/四位小数/非整秒 400，21 航点 400，版本不符 409")
    void badRequestsOverHttp() throws Exception {
        createRoute("r1", "req-route-1");

        // 时刻重复 → 400
        String dupTime = """
                [{"x":0,"y":0,"arrivalTime":"2026-10-04T08:00:00Z"},
                 {"x":50,"y":0,"arrivalTime":"2026-10-04T08:00:00Z"}]""";
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-b1", "op", "r1", 1, 0, dupTime)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ARRIVAL_TIME"));

        // 点数与航线不一致（航线 2 点，提交 3 点）→ 400
        String threePoints = """
                [{"x":0,"y":0,"arrivalTime":"2026-10-04T08:00:00Z"},
                 {"x":50,"y":0,"arrivalTime":"2026-10-04T08:00:01Z"},
                 {"x":100,"y":0,"arrivalTime":"2026-10-04T08:00:02Z"}]""";
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-b2", "op", "r1", 1, 0, threePoints)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POINT_COUNT_MISMATCH"));

        // 坐标 4 位小数 → 400
        String precise = """
                [{"x":0.0001,"y":0,"arrivalTime":"2026-10-04T08:00:00Z"},
                 {"x":50,"y":0,"arrivalTime":"2026-10-04T08:00:01Z"}]""";
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-b3", "op", "r1", 1, 0, precise)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COORDINATE_PRECISION"));

        // 非 UTC 整秒 → 400
        String millis = """
                [{"x":0,"y":0,"arrivalTime":"2026-10-04T08:00:00.500Z"},
                 {"x":50,"y":0,"arrivalTime":"2026-10-04T08:00:01Z"}]""";
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-b4", "op", "r1", 1, 0, millis)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARRIVAL_TIME_NOT_WHOLE_SECONDS"));

        // 21 个航点超过上限 20 → 400（Bean Validation）
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append("{\"x\":").append(i * 10)
                    .append(",\"y\":0,\"arrivalTime\":\"2026-10-04T08:00:")
                    .append(String.format("%02d", i)).append("Z\"}");
        }
        many.append(']');
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-b5", "op", "r1", 1, 0, many.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // expectedRouteVersion 不符 → 409
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-b6", "op", "r1", 9, 0, TWO_POINTS_100M_2S)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 航线不存在 → 404
        mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-b7", "op", "ghost", 1, 0, TWO_POINTS_100M_2S)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    @DisplayName("STALE：替换航线后当前定时结论失效，历史保留冻结坐标与时刻")
    void staleOverHttp() throws Exception {
        createRoute("r1", "req-route-1");
        MvcResult created = mockMvc.perform(post("/api/airspace/timed-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timedBody("tr-stale", "op", "r1", 1, 0, TWO_POINTS_100M_2S)))
                .andExpect(status().isCreated())
                .andReturn();
        String reviewId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        mockMvc.perform(post("/api/airspace/routes/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","expectedVersion":1,
                                 "points":[{"x":0,"y":0},{"x":2000,"y":0}],
                                 "requestId":"req-replace-1"}"""))
                .andExpect(status().isOk());

        // 当前结论 STALE
        mockMvc.perform(get("/api/airspace/routes/r1/current-timed-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"))
                .andExpect(jsonPath("$.current").value(false));

        // 历史保留冻结的坐标、时刻与原结论
        mockMvc.perform(get("/api/airspace/timed-reviews/" + reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.points[1].x").value(100))
                .andExpect(jsonPath("$.points[1].arrivalTime").value("2026-10-04T08:00:02Z"));
    }
}