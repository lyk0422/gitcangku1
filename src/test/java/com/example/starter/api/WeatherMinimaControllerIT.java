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
 * 气象最低标准 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由与统一异常处理）：
 * 覆盖标准配置 422 分支、预测上传、WEATHER_BLOCKED 审查、占用创建与 STALE 拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("气象最低标准 HTTP API 端到端")
class WeatherMinimaControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM occupation");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM weather_forecast");
        jdbc.update("DELETE FROM weather_minima");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET standards_version = 0, forecast_version = 0 WHERE id = 1");
    }

    @Test
    @DisplayName("主流程：标准+预测+WEATHER_BLOCKED 审查+时效冻结+占用")
    void weatherFlowOverHttp() throws Exception {
        // 建区（空域版本 1）与航线（穿过该区域）
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-w-zone"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-w-route",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());

        // 负值标准 → 422
        mockMvc.perform(post("/api/airspace/weather/minima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","operator":"ops-1","requestId":"req-w-bad",
                                 "windows":[{"effectiveFrom":0,"effectiveTo":1000,
                                             "minVisibilityMeters":-1,"minCloudBaseMeters":300}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NEGATIVE_MINIMA"));

        // 合法标准 → 200，标准版本 1
        mockMvc.perform(post("/api/airspace/weather/minima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","operator":"ops-1","requestId":"req-w-minima",
                                 "windows":[{"effectiveFrom":0,"effectiveTo":1000,
                                             "minVisibilityMeters":5000,"minCloudBaseMeters":300}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.standardsVersion").value(1))
                .andExpect(jsonPath("$.data.windowCount").value(1));

        // 标准明细查询
        mockMvc.perform(get("/api/airspace/zones/z1/weather-minima"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows[0].minVisibilityMeters").value(5000));

        // 上传预测（能见度不足）→ 201，预测版本 1
        mockMvc.perform(post("/api/airspace/weather/forecasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"ops-1","requestId":"req-w-forecast",
                                 "entries":[{"zoneId":"z1","validFrom":0,"validTo":1000,
                                             "visibilityMeters":3200,"cloudBaseMeters":250}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.forecastVersion").value(1));

        // 提交预测版本审查 → WEATHER_BLOCKED，含实际值/要求值/差额
        MvcResult reviewResult = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":1,
                                 "forecastVersion":1,"requestId":"req-w-review"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("WEATHER_BLOCKED"))
                .andExpect(jsonPath("$.data.weatherViolations[0].actualValue").value(3200))
                .andExpect(jsonPath("$.data.weatherViolations[0].requiredValue").value(5000))
                .andExpect(jsonPath("$.data.weatherViolations[0].deficit").value(1800))
                .andReturn();
        String reviewId = objectMapper.readTree(reviewResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 非 CLEAR 审核不能创建占用 → 409
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","routeId":"r1","operator":"ops-1",
                                 "requestId":"req-w-occ-1"}""".formatted(reviewId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OCCUPATION_NOT_CLEARED"));

        // 诊断查询：当前全部版本匹配，有效结论即原结论
        mockMvc.perform(get("/api/airspace/reviews/" + reviewId + "/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("WEATHER_BLOCKED"))
                .andExpect(jsonPath("$.stale").value(false));

        // 预测缺失 → 422：标准时段 [0,1000) 只有 [0,400) 的预测覆盖
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","requestId":"req-w-route2",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/weather/forecasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"ops-1","requestId":"req-w-forecast2",
                                 "entries":[{"zoneId":"z1","validFrom":0,"validTo":400,
                                             "visibilityMeters":8000,"cloudBaseMeters":600}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":1,
                                 "forecastVersion":2,"requestId":"req-w-review2"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORECAST_MISSING"));

        // 气象修订使旧审查 STALE，但历史结论不被改写
        mockMvc.perform(get("/api/airspace/routes/r1/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"));
        mockMvc.perform(get("/api/airspace/reviews/" + reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("WEATHER_BLOCKED"));

        // 不经过任何区域的航线在预测版本 2 下审查 → CLEAR
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r3","requestId":"req-w-route3",
                                 "points":[{"x":0,"y":50},{"x":100,"y":50}]}"""))
                .andExpect(status().isCreated());
        MvcResult clearResult = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r3","routeVersion":1,"airspaceVersion":1,
                                 "forecastVersion":2,"requestId":"req-w-review3"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andReturn();
        String clearReviewId = objectMapper.readTree(
                clearResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // CLEAR 时可创建占用；气象再次修订后审核 STALE，新占位被拒绝
        mockMvc.perform(post("/api/airspace/weather/forecasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"ops-1","requestId":"req-w-forecast3",
                                 "entries":[{"zoneId":"z1","validFrom":0,"validTo":1000,
                                             "visibilityMeters":8000,"cloudBaseMeters":600}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/airspace/routes/r3/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"));
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","routeId":"r3","operator":"ops-1",
                                 "requestId":"req-w-occ-2"}""".formatted(clearReviewId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_STALE"));
    }

    @Test
    @DisplayName("CLEAR 审查创建占用并固化气象版本；批量审核原子性")
    void occupationAndBatchOverHttp() throws Exception {
        // 不经过任何区域的航线 + 完整预测覆盖
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-o-zone"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-o-route1",
                                 "points":[{"x":0,"y":50},{"x":100,"y":50}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","requestId":"req-o-route2",
                                 "points":[{"x":0,"y":60},{"x":100,"y":60}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/weather/forecasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"ops-1","requestId":"req-o-forecast",
                                 "entries":[{"zoneId":"z1","validFrom":0,"validTo":1000,
                                             "visibilityMeters":8000,"cloudBaseMeters":600}]}"""))
                .andExpect(status().isCreated());

        // 批量审核：两条航线一次成功，结果顺序与提交一致
        MvcResult batchResult = mockMvc.perform(post("/api/airspace/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"ops-1","requestId":"req-o-batch",
                                 "items":[{"routeId":"r1","routeVersion":1,"airspaceVersion":1,
                                           "forecastVersion":1},
                                          {"routeId":"r2","routeVersion":1,"airspaceVersion":1,
                                           "forecastVersion":1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.results[0].routeId").value("r1"))
                .andExpect(jsonPath("$.data.results[0].conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.results[1].routeId").value("r2"))
                .andReturn();
        String reviewId = objectMapper.readTree(batchResult.getResponse().getContentAsString())
                .path("data").path("results").get(0).path("reviewId").asText();

        // 创建占用：固化审查时的气象版本
        MvcResult occResult = mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","routeId":"r1","operator":"ops-1",
                                 "requestId":"req-o-occ"}""".formatted(reviewId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.forecastVersion").value(1))
                .andExpect(jsonPath("$.data.standardsVersion").value(0))
                .andReturn();
        String occupationId = objectMapper.readTree(occResult.getResponse().getContentAsString())
                .path("data").path("occupationId").asText();

        // 占用明细与航线占用列表查询
        mockMvc.perform(get("/api/airspace/occupations/" + occupationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId));
        mockMvc.perform(get("/api/airspace/routes/r1/occupations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].occupationId").value(occupationId));

        // 历史审核列表查询（稳定排序）
        mockMvc.perform(get("/api/airspace/routes/r1/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reviewId").value(reviewId))
                .andExpect(jsonPath("$[0].conclusion").value("CLEAR"));
    }
}
