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
 * 运动间隔许可 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由、校验与统一异常处理）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("运动间隔许可 HTTP API 端到端")
class PermitControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
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
    @DisplayName("主流程：创建许可 201，同键重放，对向冲突 422 含冲突许可 ID，撤销后放行")
    void permitLifecycleOverHttp() throws Exception {
        createRoute("ra", "req-route-a", "[{\"x\":0,\"y\":0},{\"x\":100,\"y\":0}]");
        createRoute("rb", "req-route-b", "[{\"x\":100,\"y\":0},{\"x\":0,\"y\":0}]");

        // 创建许可 → 201 ACTIVE
        MvcResult created = mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-p1","operator":"alice","routeId":"ra",
                                 "routeVersion":1,"startEpochSecond":0,"endEpochSecond":11}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.operator").value("alice"))
                .andReturn();
        String permitId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("permitId").asText();

        // 同键同参重放 → 同一 permitId，replayed=true
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-p1","operator":"alice","routeId":"ra",
                                 "routeVersion":1,"startEpochSecond":0,"endEpochSecond":11}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.permitId").value(permitId));

        // 对向同时段许可 → 422，返回冲突许可 ID
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-p2","operator":"bob","routeId":"rb",
                                 "routeVersion":1,"startEpochSecond":0,"endEpochSecond":11}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERMIT_SEPARATION_CONFLICT"))
                .andExpect(jsonPath("$.conflictPermitIds[0]").value(permitId));

        // 查询许可 → 200，含轨迹快照
        mockMvc.perform(get("/api/airspace/permits/{permitId}", permitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.pointsSnapshot[0].x").value(0))
                .andExpect(jsonPath("$.pointsSnapshot[1].x").value(100));

        // 撤销 → 200 REVOKED；再次撤销 → 409
        mockMvc.perform(post("/api/airspace/permits/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-rv1\",\"permitId\":\"" + permitId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
        mockMvc.perform(post("/api/airspace/permits/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-rv2\",\"permitId\":\"" + permitId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERMIT_ALREADY_REVOKED"));

        // 撤销释放占用：原冲突许可现在成功
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-p3","operator":"bob","routeId":"rb",
                                 "routeVersion":1,"startEpochSecond":0,"endEpochSecond":11}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("失败分支：参数校验 400、非法区间 400、同键异参 409、查询不存在 404")
    void failureBranchesOverHttp() throws Exception {
        createRoute("ra", "req-route-a", "[{\"x\":0,\"y\":0},{\"x\":100,\"y\":0}]");

        // 缺少 operator → 400
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-bad1","routeId":"ra",
                                 "routeVersion":1,"startEpochSecond":0,"endEpochSecond":11}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // start >= end → 400 INVALID_PERMIT_INTERVAL
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-bad2","operator":"alice","routeId":"ra",
                                 "routeVersion":1,"startEpochSecond":11,"endEpochSecond":11}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERMIT_INTERVAL"));

        // 同键异参 → 409
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-dup","operator":"alice","routeId":"ra",
                                 "routeVersion":1,"startEpochSecond":0,"endEpochSecond":11}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-dup","operator":"alice","routeId":"ra",
                                 "routeVersion":1,"startEpochSecond":0,"endEpochSecond":12}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));

        // 查询不存在的许可 → 404
        mockMvc.perform(get("/api/airspace/permits/pm_ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERMIT_NOT_FOUND"));
    }
}