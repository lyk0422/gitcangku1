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
 * 气象最低标准与占用 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由、校验、
 * 统一异常处理与事务）：覆盖标准配置/预测上传/审查/占用主流程、
 * 422 失败分支、时效诊断与幂等重放标记。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("气象最低标准与占用 HTTP API 端到端")
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
        jdbc.update("UPDATE weather_meta SET weather_version = 0, forecast_version = 0, "
                + "duty_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    @Test
    @DisplayName("主流程：标准配置→预测上传→WEATHER_BLOCKED→达标 CLEAR→占用→时效诊断")
    void fullWeatherFlowOverHttp() throws Exception {
        // 建航线与区域
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-1"}"""))
                .andExpect(status().isCreated());

        // 配置气象最低标准 → 201，气象标准版本 1
        mockMvc.perform(post("/api/airspace/weather/minima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","operator":"op-1","requestId":"req-minima-1",
                                 "windows":[{"startMinute":480,"endMinute":720,
                                             "minVisibilityM":5000,"minCloudBaseM":500}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.weatherVersion").value(1))
                .andExpect(jsonPath("$.data.windows[0].minVisibilityM").value(5000));

        // 标准明细查询
        mockMvc.perform(get("/api/airspace/weather/minima/z1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weatherVersion").value(1))
                .andExpect(jsonPath("$.windows[0].startMinute").value(480));

        // 上传预测（不达标）→ 201，预测版本 1
        mockMvc.perform(post("/api/airspace/weather/forecasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"op-1","requestId":"req-forecast-1",
                                 "entries":[{"zoneId":"z1","startMinute":480,"endMinute":720,
                                             "visibilityM":3000,"cloudBaseM":300}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.forecastVersion").value(1))
                .andExpect(jsonPath("$.data.entryCount").value(1));

        // 审查 → WEATHER_BLOCKED，含实际值/要求值/差额
        MvcResult blockedResult = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":1,
                                 "forecastVersion":1,"operator":"op-1",
                                 "requestId":"req-review-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("WEATHER_BLOCKED"))
                .andExpect(jsonPath("$.data.weatherViolations[0].metric").value("CLOUD_BASE"))
                .andExpect(jsonPath("$.data.weatherViolations[0].actualValue").value(300))
                .andExpect(jsonPath("$.data.weatherViolations[0].requiredValue").value(500))
                .andExpect(jsonPath("$.data.weatherViolations[0].deficit").value(200))
                .andExpect(jsonPath("$.data.weatherViolations[1].metric").value("VISIBILITY"))
                .andExpect(jsonPath("$.data.weatherViolations[1].deficit").value(2000))
                .andReturn();
        String blockedReviewId = objectMapper.readTree(
                blockedResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 非 CLEAR 审核不能创建占用 → 409
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","operator":"op-1","requestId":"req-occ-bad"}
                                """.formatted(blockedReviewId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OCCUPATION_NOT_CLEAR"));

        // 上传达标预测（版本 2）→ 重新审查 CLEAR
        mockMvc.perform(post("/api/airspace/weather/forecasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"op-1","requestId":"req-forecast-2",
                                 "entries":[{"zoneId":"z1","startMinute":480,"endMinute":720,
                                             "visibilityM":6000,"cloudBaseM":800}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.forecastVersion").value(2));
        MvcResult clearResult = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":1,
                                 "forecastVersion":2,"operator":"op-1",
                                 "requestId":"req-review-2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.weatherVersion").value(1))
                .andExpect(jsonPath("$.data.forecastVersion").value(2))
                .andExpect(jsonPath("$.data.dutyVersion").value(0))
                .andReturn();
        String clearReviewId = objectMapper.readTree(
                clearResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 创建占用 → 201，固化审查时气象版本
        MvcResult occResult = mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","operator":"op-1","requestId":"req-occ-1"}
                                """.formatted(clearReviewId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.weatherVersion").value(1))
                .andExpect(jsonPath("$.data.forecastVersion").value(2))
                .andExpect(jsonPath("$.data.dutyVersion").value(0))
                .andReturn();
        String occupationId = objectMapper.readTree(
                occResult.getResponse().getContentAsString())
                .path("data").path("occupationId").asText();

        // 占用明细与历史查询
        mockMvc.perform(get("/api/airspace/occupations/" + occupationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(clearReviewId));
        mockMvc.perform(get("/api/airspace/routes/r1/occupations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].occupationId").value(occupationId));

        // 换班 → 时效诊断值班维度 STALE，占用历史记录不变
        mockMvc.perform(post("/api/airspace/duty/advance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"op-1","requestId":"req-duty-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dutyVersion").value(1));
        mockMvc.perform(get("/api/airspace/reviews/" + clearReviewId + "/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.dimensions[4].dimension").value("DUTY"))
                .andExpect(jsonPath("$.dimensions[4].frozenValue").value(0))
                .andExpect(jsonPath("$.dimensions[4].currentValue").value(1))
                .andExpect(jsonPath("$.dimensions[4].stale").value(true));
        mockMvc.perform(get("/api/airspace/routes/r1/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"));
        // 历史结论不被改写
        mockMvc.perform(get("/api/airspace/reviews/" + clearReviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("CLEAR"));
        // 占用记录固化版本不变
        mockMvc.perform(get("/api/airspace/occupations/" + occupationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dutyVersion").value(0));
    }

    @Test
    @DisplayName("422：标准负值、时段重叠、预测缺失；失败不产生半成品")
    void unprocessableBranchesOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","requestId":"req-route-2",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z2","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-2"}"""))
                .andExpect(status().isCreated());

        // 标准负值 → 422
        mockMvc.perform(post("/api/airspace/weather/minima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z2","operator":"op-1","requestId":"req-minima-neg",
                                 "windows":[{"startMinute":480,"endMinute":720,
                                             "minVisibilityM":-1,"minCloudBaseM":500}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WEATHER_MINIMA_NEGATIVE"));

        // 时段重叠 → 422
        mockMvc.perform(post("/api/airspace/weather/minima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z2","operator":"op-1","requestId":"req-minima-ovl",
                                 "windows":[{"startMinute":480,"endMinute":720,
                                             "minVisibilityM":5000,"minCloudBaseM":500},
                                            {"startMinute":700,"endMinute":900,
                                             "minVisibilityM":5000,"minCloudBaseM":500}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WEATHER_WINDOW_OVERLAP"));

        // 合法配置后，缺预测审查 → 422，且不产生审核记录
        mockMvc.perform(post("/api/airspace/weather/minima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z2","operator":"op-1","requestId":"req-minima-ok",
                                 "windows":[{"startMinute":480,"endMinute":720,
                                             "minVisibilityM":5000,"minCloudBaseM":500}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":1,
                                 "operator":"op-1","requestId":"req-review-nofc"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORECAST_MISSING"));
        // 查询不到半成品
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM review", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = 'req-review-nofc'",
                Integer.class));
    }

    @Test
    @DisplayName("批量审查：任一航线缺预测整批 422 不创建；幂等重放返回首次快照")
    void batchAndIdempotencyOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"rb1","requestId":"req-route-b1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"rb2","requestId":"req-route-b2",
                                 "points":[{"x":0,"y":-50},{"x":100,"y":-50}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"zb","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-b"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/weather/minima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"zb","operator":"op-1","requestId":"req-minima-b",
                                 "windows":[{"startMinute":480,"endMinute":720,
                                             "minVisibilityM":5000,"minCloudBaseM":500}]}"""))
                .andExpect(status().isCreated());

        // 缺预测 → 整批 422，不创建任何结果
        mockMvc.perform(post("/api/airspace/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airspaceVersion":1,"operator":"op-1","requestId":"req-batch-1",
                                 "items":[{"routeId":"rb1","routeVersion":1},
                                          {"routeId":"rb2","routeVersion":1}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORECAST_MISSING"));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM review", Integer.class));

        // 上传预测后整批成功，结果按 routeId 排序
        mockMvc.perform(post("/api/airspace/weather/forecasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"op-1","requestId":"req-forecast-b",
                                 "entries":[{"zoneId":"zb","startMinute":480,"endMinute":720,
                                             "visibilityM":6000,"cloudBaseM":800}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airspaceVersion":1,"forecastVersion":1,"operator":"op-1",
                                 "requestId":"req-batch-2",
                                 "items":[{"routeId":"rb2","routeVersion":1},
                                          {"routeId":"rb1","routeVersion":1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.results[0].routeId").value("rb1"))
                .andExpect(jsonPath("$.data.results[1].routeId").value("rb2"))
                .andExpect(jsonPath("$.data.results[0].conclusion").value("CLEAR"));

        // 同键同参重放：replayed=true，结果不重复创建
        mockMvc.perform(post("/api/airspace/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airspaceVersion":1,"forecastVersion":1,"operator":"op-1",
                                 "requestId":"req-batch-2",
                                 "items":[{"routeId":"rb2","routeVersion":1},
                                          {"routeId":"rb1","routeVersion":1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.results[0].routeId").value("rb1"));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM review", Integer.class));

        // 同键异参 → 409
        mockMvc.perform(post("/api/airspace/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airspaceVersion":1,"forecastVersion":1,"operator":"op-2",
                                 "requestId":"req-batch-2",
                                 "items":[{"routeId":"rb1","routeVersion":1}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
    }
}
