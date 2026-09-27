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
 * 应急走廊 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由、校验、统一异常处理）：
 * 覆盖走廊创建/让渡主流程、409/422/400 失败分支、明细与历史查询及幂等重放标记。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("应急走廊 HTTP API 端到端")
class EmergencyCorridorControllerIT {

    private static final long W0 = 1_700_000_000_000L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM occupation_reapply");
        jdbc.update("DELETE FROM corridor_yield");
        jdbc.update("DELETE FROM occupation");
        jdbc.update("DELETE FROM emergency_corridor");
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
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""
                                .formatted(routeId, requestId)))
                .andExpect(status().isCreated());
    }

    private void createOccupation(String occupationId, String routeId, String kind,
                                  boolean yieldable, String requestId) throws Exception {
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","occupationId":"%s","routeId":"%s",
                                 "routeVersion":1,"kind":"%s",
                                 "startUtc":%d,"endUtc":%d,"altMin":0,"altMax":100,
                                 "yieldable":%s}"""
                                .formatted(requestId, occupationId, routeId, kind,
                                        W0, W0 + 1000, yieldable)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("主流程：占用→走廊让渡→明细/历史查询→撤销→重新申请")
    void corridorLifecycleOverHttp() throws Exception {
        createRoute("r1", "req-route-1");
        createOccupation("occ1", "r1", "REGULAR", true, "req-occ-1");

        // 创建走廊 → 201，让渡 1 个占用
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-corridor-1","corridorId":"c1",
                                 "xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "startUtc":%d,"endUtc":%d,"altMin":0,"altMax":100,
                                 "priority":1,"operator":"dispatcher-1"}"""
                                .formatted(W0, W0 + 1000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.yieldedCount").value(1))
                .andExpect(jsonPath("$.data.yieldedOccupationIds[0]").value("occ1"));

        // 同键同参重放 → replayed=true
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-corridor-1","corridorId":"c1",
                                 "xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "startUtc":%d,"endUtc":%d,"altMin":0,"altMax":100,
                                 "priority":1,"operator":"dispatcher-1"}"""
                                .formatted(W0, W0 + 1000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.yieldedCount").value(1));

        // 走廊明细与让渡历史
        mockMvc.perform(get("/api/airspace/corridors/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revokedAt").doesNotExist());
        mockMvc.perform(get("/api/airspace/corridors/c1/yields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].occupationId").value("occ1"))
                .andExpect(jsonPath("$[0].corridorVersion").value(1));

        // 占用明细：YIELDED 且关联让渡记录
        mockMvc.perform(get("/api/airspace/occupations/occ1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("YIELDED"))
                .andExpect(jsonPath("$.yield.corridorId").value("c1"));

        // 撤销走廊 → 200，版本加一
        mockMvc.perform(post("/api/airspace/corridors/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-revoke-1\",\"corridorId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.version").value(2));

        // 重新申请 → 201，新占用 ACTIVE
        mockMvc.perform(post("/api/airspace/occupations/reapply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-reapply-1","sourceOccupationId":"occ1",
                                 "newOccupationId":"occ1-b"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.newOccupationId").value("occ1-b"));

        // 占用历史：创建/让渡/被接替事件
        mockMvc.perform(get("/api/airspace/occupations/occ1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("YIELDED"))
                .andExpect(jsonPath("$.events.length()").value(3));
    }

    @Test
    @DisplayName("失败分支：常规占用命中走廊 409 含走廊标识；不可让渡 422；参数 400")
    void failureBranchesOverHttp() throws Exception {
        createRoute("r1", "req-route-1");
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-corridor-1","corridorId":"c1",
                                 "xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "startUtc":%d,"endUtc":%d,"altMin":0,"altMax":100,
                                 "priority":1,"operator":"dispatcher-1"}"""
                                .formatted(W0, W0 + 1000)))
                .andExpect(status().isCreated());

        // 常规占用命中有效走廊 → 409，details 给出走廊标识
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-occ-c","occupationId":"occ-c","routeId":"r1",
                                 "routeVersion":1,"kind":"REGULAR",
                                 "startUtc":%d,"endUtc":%d,"altMin":0,"altMax":100,
                                 "yieldable":true}"""
                                .formatted(W0, W0 + 1000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRIDOR_CONFLICT"))
                .andExpect(jsonPath("$.details.corridorIds[0]").value("c1"));

        // 应急占用允许 → 201
        createOccupation("occ-e", "r1", "EMERGENCY", false, "req-occ-e");

        // 不可让渡应急占用不影响走廊；另建不可让渡常规占用后新走廊 422
        createRoute("r2", "req-route-2");
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-occ-ny","occupationId":"occ-ny","routeId":"r2",
                                 "routeVersion":1,"kind":"REGULAR",
                                 "startUtc":%d,"endUtc":%d,"altMin":0,"altMax":100,
                                 "yieldable":false}"""
                                .formatted(W0 + 2000, W0 + 3000)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-corridor-2","corridorId":"c2",
                                 "xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "startUtc":%d,"endUtc":%d,"altMin":0,"altMax":100,
                                 "priority":1,"operator":"dispatcher-1"}"""
                                .formatted(W0 + 2000, W0 + 3000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OCCUPATION_NOT_YIELDABLE"))
                .andExpect(jsonPath("$.details.nonYieldableCount").value(1))
                .andExpect(jsonPath("$.details.nonYieldableOccupationIds[0]").value("occ-ny"));

        // 非法时段 → 400
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-corridor-3","corridorId":"c3",
                                 "xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "startUtc":%d,"endUtc":%d,"altMin":0,"altMax":100,
                                 "priority":1,"operator":"dispatcher-1"}"""
                                .formatted(W0 + 1000, W0 + 1000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_WINDOW"));

        // 缺失必填字段 → 400 VALIDATION_FAILED
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-corridor-4\",\"corridorId\":\"c4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 查询不存在的走廊/占用 → 404
        mockMvc.perform(get("/api/airspace/corridors/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORRIDOR_NOT_FOUND"));
        mockMvc.perform(get("/api/airspace/occupations/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OCCUPATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("诊断查询只读：返回相交走廊与占用")
    void diagnosticsOverHttp() throws Exception {
        createRoute("r1", "req-route-1");
        createOccupation("occ1", "r1", "REGULAR", true, "req-occ-1");
        mockMvc.perform(get("/api/airspace/diagnostics/corridor-check")
                        .param("routeId", "r1")
                        .param("startUtc", String.valueOf(W0))
                        .param("endUtc", String.valueOf(W0 + 1000))
                        .param("altMin", "0")
                        .param("altMax", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hitCorridorIds.length()").value(0))
                .andExpect(jsonPath("$.activeRegularOccupationIds[0]").value("occ1"));
    }
}
