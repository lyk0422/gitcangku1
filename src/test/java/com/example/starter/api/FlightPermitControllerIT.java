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
 * 运动间隔许可 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由、校验与事务）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("运动间隔许可 HTTP API 端到端")
class FlightPermitControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM flight_permit");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private void createRoute(String routeId, int x1, int y1, int x2, int y2) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"req-route-%s",
                                 "points":[{"x":%d,"y":%d},{"x":%d,"y":%d}]}"""
                                .formatted(routeId, routeId, x1, y1, x2, y2)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("主流程：创建许可 201，同键重放，查询快照，撤销后历史保留")
    void permitLifecycleOverHttp() throws Exception {
        createRoute("r1", 0, 0, 100, 0);
        String body = """
                {"requestId":"pm-req-1","operator":"atc-1","routeId":"r1",
                 "routeVersion":1,"startSecond":1000,"endSecond":1010}""";
        MvcResult created = mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.routeVersion").value(1))
                .andExpect(jsonPath("$.data.ax").value(0.0))
                .andExpect(jsonPath("$.data.bx").value(100.0))
                .andReturn();
        String permitId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("permitId").asText();

        // 同键同参重放：返回首次完整响应
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.permitId").value(permitId));

        // 同键异参 → 409
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pm-req-1","operator":"atc-2","routeId":"r1",
                                 "routeVersion":1,"startSecond":1000,"endSecond":1010}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));

        // 撤销 → 200 REVOKED；历史轨迹保留可查
        mockMvc.perform(post("/api/airspace/permits/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pm-revoke-1","operator":"atc-1",
                                 "permitId":"%s"}""".formatted(permitId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
        mockMvc.perform(get("/api/airspace/permits/" + permitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.startSecond").value(1000))
                .andExpect(jsonPath("$.bx").value(100.0));
        // 重复撤销 → 409
        mockMvc.perform(post("/api/airspace/permits/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pm-revoke-2","operator":"atc-1",
                                 "permitId":"%s"}""".formatted(permitId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERMIT_ALREADY_REVOKED"));
    }

    @Test
    @DisplayName("合成对向直线 5.5 秒相遇：422 返回冲突许可 ID，推迟后 201")
    void opposingLinesConflictOverHttp() throws Exception {
        createRoute("east", 0, 0, 100, 0);
        createRoute("west", 100, 0, 0, 0);
        MvcResult first = mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pm-east","operator":"atc","routeId":"east",
                                 "routeVersion":1,"startSecond":0,"endSecond":10}"""))
                .andExpect(status().isCreated())
                .andReturn();
        String firstPermitId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("permitId").asText();

        // 对向 [1,11)：5.5 秒相遇 → 422 且返回冲突许可 ID
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pm-west","operator":"atc","routeId":"west",
                                 "routeVersion":1,"startSecond":1,"endSecond":11}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERMIT_SEPARATION_CONFLICT"))
                .andExpect(jsonPath("$.conflictPermitIds[0]").value(firstPermitId));

        // 推迟到前一结束后 → 201
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pm-west-2","operator":"atc","routeId":"west",
                                 "routeVersion":1,"startSecond":10,"endSecond":20}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("参数与资源错误：缺操作者 400、区间非法 400、航线缺失 404、许可缺失 404")
    void validationAndNotFoundOverHttp() throws Exception {
        createRoute("r9", 0, 0, 100, 0);
        // 缺 operator → 400 校验失败
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pm-bad-1","routeId":"r9",
                                 "routeVersion":1,"startSecond":0,"endSecond":10}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // startSecond >= endSecond → 400
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pm-bad-2","operator":"atc","routeId":"r9",
                                 "routeVersion":1,"startSecond":10,"endSecond":10}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERMIT_INTERVAL"));
        // 航线不存在 → 404
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pm-bad-3","operator":"atc","routeId":"ghost",
                                 "routeVersion":1,"startSecond":0,"endSecond":10}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
        // 许可不存在 → 404
        mockMvc.perform(get("/api/airspace/permits/pm_ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERMIT_NOT_FOUND"));
        // 失败不占键：pm-bad-3 可复用于合法请求
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pm-bad-3","operator":"atc","routeId":"r9",
                                 "routeVersion":1,"startSecond":0,"endSecond":10}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
    }
}