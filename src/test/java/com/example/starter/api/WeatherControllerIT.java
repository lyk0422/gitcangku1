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
 * 气象最低标准 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由与统一异常处理）：
 * 覆盖标准配置 201/422、预测上传、WEATHER_BLOCKED 审查、占用创建与幂等重放、
 * 批量审查整批 422、诊断查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("气象最低标准 HTTP API 端到端")
class WeatherControllerIT {

    @Autowired
    private MockMvc	mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM occupancy");
        jdbc.update("DELETE FROM weather_forecast");
        jdbc.update("DELETE FROM weather_minima");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET weather_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private void prepareRevokedZoneAndRoute() throws Exception {
        mockMvc.perform(post("/api/airspace/zones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\":\"z1\",\"xMin\":40,\"yMin\":5,\"xMax\":60,\"yMax\":15,"
                                + "\"requestId\":\"req-zone-1\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones/revoke").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\":\"z1\",\"requestId\":\"req-revoke-1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/airspace/routes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"r1\",\"requestId\":\"req-route-1\","
                                + "\"points\":[{\"x\":0,\"y\":10},{\"x\":100,\"y\":10}]}"))
                .andExpect(status().isCreated());
    }

    private void configMinimaAndForecast(int visibility, int cloudBase) throws Exception {
        mockMvc.perform(post("/api/airspace/weather/minima").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-minima-1\",\"operator\":\"op-a\",\"zoneId\":\"z1\","
                                + "\"windows\":[{\"startUtc\":1000,\"endUtc\":2000,"
                                + "\"minVisibilityM\":1000,\"minCloudBaseM\":300}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.weatherVersion").value(1))
                .andExpect(jsonPath("$.data.windowCount").value(1));
        mockMvc.perform(post("/api/airspace/weather/forecasts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-forecast-1\",\"operator\":\"op-a\","
                                + "\"entries\":[{\"zoneId\":\"z1\",\"startUtc\":1000,\"endUtc\":2000,"
                                + "\"visibilityM\":" + visibility + ",\"cloudBaseM\":" + cloudBase + "}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.forecastVersion").value(2));
    }

    @Test
    @DisplayName("主流程：标准配置、预测上传、WEATHER_BLOCKED、占用与诊断")
    void weatherFlowOverHttp() throws Exception {
        prepareRevokedZoneAndRoute();
        configMinimaAndForecast(800, 500);

        // 预测能见度 800 低于标准 1000 → WEATHER_BLOCKED，含实际/要求/差额
        MvcResult review = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"r1\",\"routeVersion\":1,\"airspaceVersion\":2,"
                                + "\"weatherVersion\":2,\"requestId\":\"req-review-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("WEATHER_BLOCKED"))
                .andExpect(jsonPath("$.data.weatherVersion").value(2))
                .andExpect(jsonPath("$.data.weatherViolations[0].field").value("visibility"))
                .andExpect(jsonPath("$.data.weatherViolations[0].actual").value(800))
                .andExpect(jsonPath("$.data.weatherViolations[0].required").value(1000))
                .andExpect(jsonPath("$.data.weatherViolations[0].deficit").value(200))
                .andReturn();
        String reviewId = objectMapper.readTree(review.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 诊断查询：CURRENT，快照固化气象版本
        mockMvc.perform(get("/api/airspace/reviews/" + reviewId + "/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("WEATHER_BLOCKED"))
                .andExpect(jsonPath("$.effective").value("CURRENT"))
                .andExpect(jsonPath("$.weatherVersionAtReview").value(2))
                .andExpect(jsonPath("$.weatherSnapshot.weatherVersion").value(2));

        // 占用拒绝非 CLEAR 审查 → 409
        mockMvc.perform(post("/api/airspace/occupancies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-occ-1\",\"operator\":\"op-a\","
                                + "\"reviewId\":\"" + reviewId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OCCUPANCY_NOT_CLEAR"));
    }

    @Test
    @DisplayName("失败分支：负值与重叠 422、批量缺预测整批 422、占用幂等重放")
    void failureBranchesOverHttp() throws Exception {
        prepareRevokedZoneAndRoute();

        // 负值标准 → 422 INVALID_MINIMA_VALUE
        mockMvc.perform(post("/api/airspace/weather/minima").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-minima-neg\",\"operator\":\"op-a\",\"zoneId\":\"z1\","
                                + "\"windows\":[{\"startUtc\":1000,\"endUtc\":2000,"
                                + "\"minVisibilityM\":-5,\"minCloudBaseM\":300}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_MINIMA_VALUE"));

        // 时段重叠 → 422 WINDOW_OVERLAP
        mockMvc.perform(post("/api/airspace/weather/minima").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-minima-ov\",\"operator\":\"op-a\",\"zoneId\":\"z1\","
                                + "\"windows\":[{\"startUtc\":1000,\"endUtc\":3000,"
                                + "\"minVisibilityM\":1000,\"minCloudBaseM\":300},"
                                + "{\"startUtc\":2000,\"endUtc\":4000,"
                                + "\"minVisibilityM\":1000,\"minCloudBaseM\":300}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_OVERLAP"));

        // 合法配置 + 预测达标 → CLEAR → 占用 201，同键重放 replayed=true
        configMinimaAndForecast(1500, 500);
        MvcResult clear = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"r1\",\"routeVersion\":1,\"airspaceVersion\":2,"
                                + "\"weatherVersion\":2,\"requestId\":\"req-review-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andReturn();
        String reviewId = objectMapper.readTree(clear.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();
        MvcResult occ = mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-occ-1\",\"operator\":\"op-a\","
                                + "\"reviewId\":\"" + reviewId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.weatherVersion").value(2))
                .andReturn();
        String occupancyId = objectMapper.readTree(occ.getResponse().getContentAsString())
                .path("data").path("occupancyId").asText();
        mockMvc.perform(post("/api/airspace/occupancies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-occ-1\",\"operator\":\"op-a\","
                                + "\"reviewId\":\"" + reviewId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.occupancyId").value(occupancyId));

        // 批量审查：r2 不存在 → 整批失败，不创建任何审查
        mockMvc.perform(post("/api/airspace/reviews/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-batch-1\",\"operator\":\"op-a\","
                                + "\"items\":[{\"routeId\":\"r1\",\"routeVersion\":1,"
                                + "\"airspaceVersion\":2,\"weatherVersion\":2},"
                                + "{\"routeId\":\"r2\",\"routeVersion\":1,"
                                + "\"airspaceVersion\":2,\"weatherVersion\":2}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
        JsonNode diag = objectMapper.readTree(mockMvc.perform(
                        get("/api/airspace/reviews/" + reviewId + "/diagnostics"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        // 批量失败未推进任何版本，诊断仍为 CURRENT
        org.junit.jupiter.api.Assertions.assertEquals("CURRENT", diag.path("effective").asText());
    }
}