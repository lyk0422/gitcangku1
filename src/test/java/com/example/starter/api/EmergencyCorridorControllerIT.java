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
 * 应急走廊 HTTP API 端到端测试（H2 + 真实 Spring MVC/事务）：
 * 覆盖常规占用→走廊让渡→422/409、撤销后显式重新申请、历史查询与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("应急走廊 HTTP API 端到端")
class EmergencyCorridorControllerIT {

    // 以真实当前时刻为基准的未来 UTC 窗口，避免走廊建立即自然到期
    private static final long T1 = System.currentTimeMillis() + 60_000L;
    private static final long T2 = T1 + 1_000L;
    private static final long T3 = T2 + 1_000L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM reapply_history");
        jdbc.update("DELETE FROM yield_history");
        jdbc.update("DELETE FROM route_occupancy");
        jdbc.update("DELETE FROM corridor_event");
        jdbc.update("DELETE FROM emergency_corridor");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("主流程：常规占用让渡、撤销后显式重新申请，历史可关联")
    void yieldAndReapplyFlowOverHttp() throws Exception {
        // 建航线（y=10 水平穿越走廊矩形）
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());

        // 常规占用 [T1,T2)
        MvcResult occResult = mockMvc.perform(post("/api/airspace/occupancies/regular")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":0,
                                 "startMs":%d,"endMs":%d,"altMin":0,"altMax":1000,
                                 "yieldable":true,"requestId":"req-occ-1"}""".formatted(T1, T2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("REGULAR"))
                .andExpect(jsonPath("$.data.actualDurationMs").value(T2 - T1))
                .andReturn();
        String occupancyId = objectMapper.readTree(bodyOf(occResult))
                .path("data").path("occupancyId").asText();

        // 建立相交走廊 → 占用被让渡
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "startMs":%d,"endMs":%d,"altMin":0,"altMax":1000,
                                 "priority":0,"reason":"救援通道","requestId":"req-c-1"}"""
                                .formatted(T1, T3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.durationMs").value(T3 - T1))
                .andExpect(jsonPath("$.data.yieldedOccupancies[0]").value(occupancyId));

        // 占用详情：YIELDED 且固化原因
        mockMvc.perform(get("/api/airspace/occupancies/" + occupancyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("YIELDED"))
                .andExpect(jsonPath("$.yieldCorridorId").value("c1"))
                .andExpect(jsonPath("$.yieldReason").value("救援通道"))
                .andExpect(jsonPath("$.departedMs").value(org.hamcrest.Matchers.nullValue()));

        // 走廊详情：建立事件 + 让渡历史（固化原审查版本）
        mockMvc.perform(get("/api/airspace/corridors/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].action").value("CREATED"))
                .andExpect(jsonPath("$.events[0].reason").value("救援通道"))
                .andExpect(jsonPath("$.yields[0].occupancyId").value(occupancyId))
                .andExpect(jsonPath("$.yields[0].routeVersion").value(1))
                .andExpect(jsonPath("$.yields[0].airspaceVersion").value(0));

        // 走廊有效期间重新申请同窗口 → 409 带走廊标识
        mockMvc.perform(post("/api/airspace/occupancies/reapply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldOccupancyId":"%s","routeVersion":1,"airspaceVersion":0,
                                 "startMs":%d,"endMs":%d,"altMin":0,"altMax":1000,
                                 "yieldable":true,"requestId":"req-reapply-blocked"}"""
                                .formatted(occupancyId, T1, T2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRIDOR_CONFLICT"));

        // 撤销走廊
        mockMvc.perform(post("/api/airspace/corridors/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","reason":"任务结束","requestId":"req-c-revoke"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.version").value(2));

        // 旧占用不自动恢复
        mockMvc.perform(get("/api/airspace/occupancies/" + occupancyId))
                .andExpect(jsonPath("$.status").value("YIELDED"));

        // 显式重新申请 → APPROVED，新占用 ACTIVE，历史可查
        MvcResult reapplyResult = mockMvc.perform(post("/api/airspace/occupancies/reapply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldOccupancyId":"%s","routeVersion":1,"airspaceVersion":0,
                                 "startMs":%d,"endMs":%d,"altMin":0,"altMax":1000,
                                 "yieldable":true,"requestId":"req-reapply-ok"}"""
                                .formatted(occupancyId, T1, T2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.result").value("APPROVED"))
                .andExpect(jsonPath("$.data.corridorId").value("c1"))
                .andReturn();
        String newOccupancyId = objectMapper.readTree(bodyOf(reapplyResult))
                .path("data").path("newOccupancyId").asText();

        mockMvc.perform(get("/api/airspace/occupancies/" + occupancyId + "/reapplies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].oldOccupancyId").value(occupancyId))
                .andExpect(jsonPath("$[0].newOccupancyId").value(newOccupancyId))
                .andExpect(jsonPath("$[0].result").value("APPROVED"));

        // 航线占用列表：原 YIELDED + 新 ACTIVE，按创建时刻升序
        mockMvc.perform(get("/api/airspace/routes/r1/occupancies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].occupancyId").value(occupancyId))
                .andExpect(jsonPath("$[0].status").value("YIELDED"))
                .andExpect(jsonPath("$[1].occupancyId").value(newOccupancyId))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("失败分支：不可让渡 422、命中走廊 409、应急绕过禁区 422、应急命中才允许")
    void failuresAndEmergencyFlowOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","requestId":"req-route-2",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());

        // 不可让渡常规占用
        mockMvc.perform(post("/api/airspace/occupancies/regular")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":0,
                                 "startMs":%d,"endMs":%d,"altMin":0,"altMax":1000,
                                 "yieldable":false,"requestId":"req-occ-fixed"}""".formatted(T1, T2)))
                .andExpect(status().isCreated());

        // 走廊建立整次 422，区分原因
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c2","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "startMs":%d,"endMs":%d,"altMin":0,"altMax":1000,
                                 "priority":0,"reason":"强推","requestId":"req-c-fail"}"""
                                .formatted(T1, T3)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OCCUPANCY_NOT_YIELDABLE"));

        // 走廊不存在：应急占用 404
        mockMvc.perform(post("/api/airspace/occupancies/emergency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":0,
                                 "startMs":%d,"endMs":%d,"altMin":0,"altMax":1000,
                                 "corridorId":"ghost","requestId":"req-em-ghost"}"""
                                .formatted(T1, T2)))
                .andExpect(status().isNotFound());

        // 建一条无占用冲突的走廊（不相交高度），应急航线高度不命中 → 409
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c3","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "startMs":%d,"endMs":%d,"altMin":5000,"altMax":6000,
                                 "priority":0,"reason":"高空通道","requestId":"req-c3"}"""
                                .formatted(T1, T3)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/occupancies/emergency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":0,
                                 "startMs":%d,"endMs":%d,"altMin":0,"altMax":1000,
                                 "corridorId":"c3","requestId":"req-em-miss"}""".formatted(T1, T2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRIDOR_NOT_HIT"));

        // 应急占用命中走廊 → 201
        mockMvc.perform(post("/api/airspace/occupancies/emergency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":0,
                                 "startMs":%d,"endMs":%d,"altMin":5100,"altMax":5500,
                                 "corridorId":"c3","requestId":"req-em-ok"}""".formatted(T1, T2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("EMERGENCY"))
                .andExpect(jsonPath("$.data.corridorId").value("c3"));

        // 建禁飞区封死航线后，应急占用同样 422：走廊不能绕过禁区
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-z1"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/occupancies/emergency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":1,
                                 "startMs":%d,"endMs":%d,"altMin":5100,"altMax":5500,
                                 "corridorId":"c3","requestId":"req-em-blocked"}""".formatted(T1, T2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_BLOCKED"));

        // 带时空窗常规审查命中走廊 → 409 且给出走廊标识
        mockMvc.perform(post("/api/airspace/reviews/window")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":1,
                                 "startMs":%d,"endMs":%d,"altMin":5100,"altMax":5500,
                                 "requestId":"req-win-blocked"}""".formatted(T1, T2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRIDOR_CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("c3")));
    }
}
