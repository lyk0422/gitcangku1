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
 * 运动间隔许可 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由、校验与统一异常处理）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("运动间隔许可 HTTP API 端到端")
class PermitControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM permit");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private void createRoute(String routeId, String requestId, String points) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\",\"requestId\":\"" + requestId
                                + "\",\"points\":" + points + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("主流程：创建许可 201，查询许可，重放标记，撤销 200")
    void permitLifecycleOverHttp() throws Exception {
        createRoute("r1", "req-route-1", "[{\"x\":0,\"y\":0},{\"x\":100,\"y\":0}]");

        // 创建许可 → 201 ACTIVE
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":0,
                                 "startEpochSec":0,"endEpochSec":10,
                                 "operator":"alice","requestId":"req-permit-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.routeVersion").value(1));

        // 同键同参重放 → replayed=true，同一许可
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":0,
                                 "startEpochSec":0,"endEpochSec":10,
                                 "operator":"alice","requestId":"req-permit-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        String permitId = jdbc.queryForObject("SELECT permit_id FROM permit", String.class);
        // 查询许可 → 200
        mockMvc.perform(get("/api/airspace/permits/" + permitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startEpochSec").value(0));

        // 撤销 → 200 REVOKED；再次撤销 → 409
        mockMvc.perform(post("/api/airspace/permits/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitId":"%s","operator":"alice","requestId":"req-revoke-1"}
                                """.formatted(permitId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
        mockMvc.perform(post("/api/airspace/permits/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitId":"%s","operator":"alice","requestId":"req-revoke-2"}
                                """.formatted(permitId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERMIT_ALREADY_REVOKED"));
    }

    @Test
    @DisplayName("对向相遇场景：第二许可 422 且响应携带冲突许可 ID；推迟后 201")
    void headOnConflictReturns422WithIds() throws Exception {
        createRoute("ra", "req-route-a", "[{\"x\":0,\"y\":0},{\"x\":100,\"y\":0}]");
        createRoute("rb", "req-route-b", "[{\"x\":100,\"y\":0},{\"x\":0,\"y\":0}]");

        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"ra","routeVersion":1,"airspaceVersion":0,
                                 "startEpochSec":0,"endEpochSec":11,
                                 "operator":"alice","requestId":"req-pa"}"""))
                .andExpect(status().isCreated());
        String permitA = jdbc.queryForObject("SELECT permit_id FROM permit", String.class);

        // 对向同时段：端点相隔 100 米但第 5.5 秒相遇 → 422，body 含冲突许可 ID
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"rb","routeVersion":1,"airspaceVersion":0,
                                 "startEpochSec":0,"endEpochSec":11,
                                 "operator":"alice","requestId":"req-pb"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERMIT_SEPARATION_CONFLICT"))
                .andExpect(jsonPath("$.conflictingPermitIds[0]").value(permitA));

        // 推迟到前一结束后 → 201
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"rb","routeVersion":1,"airspaceVersion":0,
                                 "startEpochSec":11,"endEpochSec":22,
                                 "operator":"alice","requestId":"req-pb2"}"""))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("校验失败：非法区间 400，缺字段 400，缺失航线 404")
    void validationFailures() throws Exception {
        createRoute("r2", "req-route-2", "[{\"x\":0,\"y\":0},{\"x\":100,\"y\":0}]");
        // start >= end → 400
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":0,
                                 "startEpochSec":10,"endEpochSec":10,
                                 "operator":"alice","requestId":"req-bad-1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERMIT_INTERVAL"));
        // 缺 operator → 400 校验失败
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":0,
                                 "startEpochSec":0,"endEpochSec":10,
                                 "requestId":"req-bad-2"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 缺失航线 → 404
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"ghost","routeVersion":1,"airspaceVersion":0,
                                 "startEpochSec":0,"endEpochSec":10,
                                 "operator":"alice","requestId":"req-bad-3"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }
}