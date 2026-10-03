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
 * 气象最低标准 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由）：
 * 覆盖标准配置、预测上传、WEATHER_BLOCKED 审核、批量整批回滚、占用与 422 错误映射。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("空域气象最低标准 HTTP API 端到端")
class WeatherMinimaControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM occupancy");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM weather_forecast");
        jdbc.update("DELETE FROM weather_minima_window");
        jdbc.update("DELETE FROM weather_region");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET duty_version = 0, forecast_version = 0 WHERE id = 1");
    }

    @Test
    @DisplayName("主流程：配置标准、上传预测、WEATHER_BLOCKED、诊断与占用拒绝")
    void weatherFlowOverHttp() throws Exception {
        // 配置气象区域最低标准 → 201，值班版本 1
        mockMvc.perform(post("/api/airspace/weather/regions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"regionId":"wr1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "windows":[{"windowStartUtc":1000,"windowEndUtc":2000,
                                             "visibilityMinM":500,"cloudBaseMinM":300}],
                                 "operator":"tester","requestId":"req-wr-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dutyVersion").value(1))
                .andExpect(jsonPath("$.data.windowCount").value(1));

        // 上传预测（能见度低于标准）→ 201，预测版本 1
        mockMvc.perform(post("/api/airspace/weather/forecasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"regionId":"wr1","windowStartUtc":1000,"windowEndUtc":2000,
                                             "visibilityM":400,"cloudBaseM":500}],
                                 "operator":"tester","requestId":"req-fc-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.forecastVersion").value(1))
                .andExpect(jsonPath("$.data.entryCount").value(1));

        // 创建航线并提交审核 → WEATHER_BLOCKED，违规明细含实际值/要求值/差额
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        MvcResult reviewResult = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":0,
                                 "forecastVersion":1,"operator":"tester","requestId":"req-rv-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("WEATHER_BLOCKED"))
                .andExpect(jsonPath("$.data.dutyVersion").value(1))
                .andExpect(jsonPath("$.data.forecastVersion").value(1))
                .andExpect(jsonPath("$.data.weatherViolations[0].metric").value("VISIBILITY"))
                .andExpect(jsonPath("$.data.weatherViolations[0].actualM").value(400))
                .andExpect(jsonPath("$.data.weatherViolations[0].requiredM").value(500))
                .andExpect(jsonPath("$.data.weatherViolations[0].deficitM").value(100))
                .andReturn();
        String reviewId = objectMapper.readTree(reviewResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 诊断查询：仍有效，快照已固化
        mockMvc.perform(get("/api/airspace/reviews/" + reviewId + "/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.frozenDutyVersion").value(1))
                .andExpect(jsonPath("$.weatherSnapshot.forecastVersion").value(1));

        // WEATHER_BLOCKED 不能创建占用 → 409
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","operator":"tester","requestId":"req-occ-1"}"""
                                .formatted(reviewId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OCCUPANCY_NOT_CLEAR"));
    }

    @Test
    @DisplayName("422 分支：负值标准、时段重叠、缺预测；批量缺预测整批不创建")
    void unprocessableBranchesOverHttp() throws Exception {
        // 负值标准 → 422
        mockMvc.perform(post("/api/airspace/weather/regions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"regionId":"wr1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "windows":[{"windowStartUtc":1000,"windowEndUtc":2000,
                                             "visibilityMinM":-1,"cloudBaseMinM":300}],
                                 "operator":"tester","requestId":"req-neg-1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WEATHER_MINIMA_NEGATIVE"));

        // 时段重叠 → 422
        mockMvc.perform(post("/api/airspace/weather/regions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"regionId":"wr1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "windows":[{"windowStartUtc":1000,"windowEndUtc":2000,
                                             "visibilityMinM":500,"cloudBaseMinM":300},
                                            {"windowStartUtc":1500,"windowEndUtc":2500,
                                             "visibilityMinM":500,"cloudBaseMinM":300}],
                                 "operator":"tester","requestId":"req-ovl-1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WEATHER_WINDOW_OVERLAP"));

        // 合法配置 + 两条航线，其中一条经过区域；批量审核缺预测 → 422 且整批不创建
        mockMvc.perform(post("/api/airspace/weather/regions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"regionId":"wr1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "windows":[{"windowStartUtc":1000,"windowEndUtc":2000,
                                             "visibilityMinM":500,"cloudBaseMinM":300}],
                                 "operator":"tester","requestId":"req-wr-2"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","requestId":"req-route-2",
                                 "points":[{"x":0,"y":-50},{"x":100,"y":-50}]}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/airspace/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"routeId":"r1","routeVersion":1,"airspaceVersion":0},
                                          {"routeId":"r2","routeVersion":1,"airspaceVersion":0}],
                                 "operator":"tester","requestId":"req-batch-1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORECAST_MISSING"));
        Integer reviewCount = jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, reviewCount);
    }

    @Test
    @DisplayName("占用：CLEAR 审核创建占用并固化气象版本，重放返回首次快照")
    void occupancyFlowOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/weather/regions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"regionId":"wr1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "windows":[{"windowStartUtc":1000,"windowEndUtc":2000,
                                             "visibilityMinM":500,"cloudBaseMinM":300}],
                                 "operator":"tester","requestId":"req-wr-1"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/weather/forecasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"regionId":"wr1","windowStartUtc":1000,"windowEndUtc":2000,
                                             "visibilityM":800,"cloudBaseM":500}],
                                 "operator":"tester","requestId":"req-fc-1"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        MvcResult reviewResult = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":0,
                                 "forecastVersion":1,"operator":"tester","requestId":"req-rv-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andReturn();
        String reviewId = objectMapper.readTree(reviewResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        String occupancyBody = """
                {"reviewId":"%s","operator":"tester","requestId":"req-occ-1"}""".formatted(reviewId);
        MvcResult occResult = mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON).content(occupancyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.dutyVersion").value(1))
                .andExpect(jsonPath("$.data.forecastVersion").value(1))
                .andReturn();
        String occupancyId = objectMapper.readTree(occResult.getResponse().getContentAsString())
                .path("data").path("occupancyId").asText();

        // 同键同参重放：返回首次完整响应快照
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON).content(occupancyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.occupancyId").value(occupancyId));

        // 占用记录可查询且保持固化版本
        mockMvc.perform(get("/api/airspace/occupancies/" + occupancyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId))
                .andExpect(jsonPath("$.dutyVersion").value(1));

        // 气象修订后旧审核 STALE，重复占用被拒绝
        mockMvc.perform(post("/api/airspace/weather/regions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"regionId":"wr1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "windows":[{"windowStartUtc":1000,"windowEndUtc":2000,
                                             "visibilityMinM":900,"cloudBaseMinM":300}],
                                 "operator":"tester","requestId":"req-wr-2"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/airspace/routes/r1/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"));
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","operator":"tester","requestId":"req-occ-2"}"""
                                .formatted(reviewId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OCCUPANCY_REVIEW_STALE"));
    }
}