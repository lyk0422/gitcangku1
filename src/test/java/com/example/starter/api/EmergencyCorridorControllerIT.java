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
 * 应急走廊与强制让渡 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由、校验、统一异常处理）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("应急走廊 HTTP API 端到端")
class EmergencyCorridorControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM corridor_yield");
        jdbc.update("DELETE FROM occupancy");
        jdbc.update("DELETE FROM emergency_corridor");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private void createRouteAndReview(String routeId, String routeType) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeType":"%s","requestId":"req-route-%s",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""
                                .formatted(routeId, routeType, routeId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":%d,
                                 "requestId":"req-review-%s"}"""
                                .formatted(routeId, currentVersion(), routeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));
    }

    private long currentVersion() {
        Long v = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
        return v == null ? -1 : v;
    }

    private void createOccupancy(String occupancyId, String routeId, boolean yieldable,
                                 long startAt, long endAt) throws Exception {
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"%s","routeId":"%s","routeVersion":1,
                                 "startAt":%d,"endAt":%d,"altitudeLower":100,"altitudeUpper":200,
                                 "yieldable":%s,"requestId":"req-occ-%s"}"""
                                .formatted(occupancyId, routeId, startAt, endAt,
                                        yieldable, occupancyId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("主流程：占用→走廊让渡→明细/历史/诊断查询→撤销→重新审查与申请")
    void corridorLifecycleOverHttp() throws Exception {
        long now = now();
        createRouteAndReview("r1", "REGULAR");
        createOccupancy("o1", "r1", true, now - 1000, now + 3_600_000);

        // 创建走廊 → 201，让渡 o1
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "startAt":%d,"endAt":%d,"altitudeLower":50,"altitudeUpper":250,
                                 "priority":1,"dispatcherId":"ops-lead","reason":"flood-rescue",
                                 "requestId":"req-corridor-1"}"""
                                .formatted(now - 2000, now + 7_200_000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.yieldedCount").value(1))
                .andExpect(jsonPath("$.data.yieldedOccupancyIds[0]").value("o1"))
                .andExpect(jsonPath("$.data.airspaceVersion").value(1))
                .andExpect(jsonPath("$.data.durationMillis").value(7_202_000L));

        // 占用明细：YIELDED 且关联让渡记录
        mockMvc.perform(get("/api/airspace/occupancies/o1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("YIELDED"))
                .andExpect(jsonPath("$.yield.corridorId").value("c1"))
                .andExpect(jsonPath("$.yield.corridorVersion").value(1))
                .andExpect(jsonPath("$.yield.reason").value("flood-rescue"))
                .andExpect(jsonPath("$.yield.airspaceVersion").value(0));

        // 走廊明细与让渡历史
        mockMvc.perform(get("/api/airspace/corridors/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.effective").value(true))
                .andExpect(jsonPath("$.revokedVersion").doesNotExist());
        mockMvc.perform(get("/api/airspace/corridors/c1/yields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].occupancyId").value("o1"));

        // 诊断：无“有效走廊 + 未让渡常规占用”冲突
        mockMvc.perform(get("/api/airspace/diagnostics/corridor-conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.violationCount").value(0));

        // 走廊生效中：常规航线重新审查 → 409 并给出走廊标识
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"req-review-blocked"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRIDOR_CONFLICT"))
                .andExpect(jsonPath("$.details.corridorId").value("c1"));

        // 撤销走廊 → 200；已让渡占用不自动恢复
        mockMvc.perform(post("/api/airspace/corridors/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","dispatcherId":"ops-lead",
                                 "requestId":"req-revoke-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.airspaceVersion").value(2));
        mockMvc.perform(get("/api/airspace/occupancies/o1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("YIELDED"));

        // 重新审查（新版本 2）→ CLEAR，显式重新申请占用 → 201
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":2,
                                 "requestId":"req-review-again"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));
        createOccupancy("o2", "r1", true, now - 1000, now + 3_600_000);

        // 航线占用历史：o1 YIELDED + o2 ACTIVE
        mockMvc.perform(get("/api/airspace/routes/r1/occupancies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].occupancyId").value("o1"))
                .andExpect(jsonPath("$[0].status").value("YIELDED"))
                .andExpect(jsonPath("$[1].occupancyId").value("o2"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("失败分支：422 阻断、403 未授权、400 校验、404 不存在、409 重复")
    void failureBranchesOverHttp() throws Exception {
        long now = now();
        createRouteAndReview("r1", "REGULAR");
        // 不可让渡占用
        createOccupancy("o1", "r1", false, now - 1000, now + 3_600_000);

        // 整次 422，details 给出阻断数量
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "startAt":%d,"endAt":%d,"altitudeLower":50,"altitudeUpper":250,
                                 "priority":1,"dispatcherId":"ops-lead",
                                 "requestId":"req-c-422"}"""
                                .formatted(now - 2000, now + 7_200_000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORRIDOR_PREEMPTION_BLOCKED"))
                .andExpect(jsonPath("$.details.blockingCount").value(1))
                .andExpect(jsonPath("$.details.blockingOccupancyIds[0]").value("o1"));
        // 回滚：走廊不存在
        mockMvc.perform(get("/api/airspace/corridors/c1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORRIDOR_NOT_FOUND"));

        // 未授权调度员 → 403
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c2","xMin":500,"yMin":500,"xMax":600,"yMax":600,
                                 "startAt":%d,"endAt":%d,"altitudeLower":0,"altitudeUpper":100,
                                 "priority":1,"dispatcherId":"intruder","requestId":"req-c-403"}"""
                                .formatted(now, now + 1000)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DISPATCHER_NOT_AUTHORIZED"));

        // 非法时段 → 400
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c3","xMin":0,"yMin":0,"xMax":10,"yMax":10,
                                 "startAt":%d,"endAt":%d,"altitudeLower":0,"altitudeUpper":100,
                                 "priority":1,"dispatcherId":"ops-lead","requestId":"req-c-400"}"""
                                .formatted(now + 1000, now)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_WINDOW"));

        // 占用不存在 → 404；起飞不存在 → 404
        mockMvc.perform(get("/api/airspace/occupancies/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OCCUPANCY_NOT_FOUND"));
        mockMvc.perform(post("/api/airspace/occupancies/takeoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"ghost","requestId":"req-t-404"}"""))
                .andExpect(status().isNotFound());

        // 合法走廊创建后同优先级重叠 → 409
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c9","xMin":500,"yMin":500,"xMax":600,"yMax":600,
                                 "startAt":%d,"endAt":%d,"altitudeLower":0,"altitudeUpper":100,
                                 "priority":1,"dispatcherId":"ops-lead","requestId":"req-c9"}"""
                                .formatted(now, now + 3_600_000)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c10","xMin":550,"yMin":550,"xMax":650,"yMax":650,
                                 "startAt":%d,"endAt":%d,"altitudeLower":50,"altitudeUpper":150,
                                 "priority":1,"dispatcherId":"ops-lead","requestId":"req-c10"}"""
                                .formatted(now + 1000, now + 4_000_000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRIDOR_OVERLAP"))
                .andExpect(jsonPath("$.details.corridorId").value("c9"));
    }

    @Test
    @DisplayName("幂等：同键同参重放 replayed=true，异参 409，失败不占键")
    void idempotencyOverHttp() throws Exception {
        long now = now();
        String body = """
                {"corridorId":"ic","xMin":500,"yMin":500,"xMax":600,"yMax":600,
                 "startAt":%d,"endAt":%d,"altitudeLower":0,"altitudeUpper":100,
                 "priority":1,"dispatcherId":"ops-lead","requestId":"idem-c-1"}"""
                .formatted(now, now + 3_600_000);
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键异参 → 409
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"ic","xMin":501,"yMin":500,"xMax":600,"yMax":600,
                                 "startAt":%d,"endAt":%d,"altitudeLower":0,"altitudeUpper":100,
                                 "priority":1,"dispatcherId":"ops-lead","requestId":"idem-c-1"}"""
                                .formatted(now, now + 3_600_000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));

        // 失败不占键：400 后同键可成功
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"ic2","xMin":5,"yMin":5,"xMax":5,"yMax":10,
                                 "startAt":%d,"endAt":%d,"altitudeLower":0,"altitudeUpper":100,
                                 "priority":1,"dispatcherId":"ops-lead","requestId":"idem-c-2"}"""
                                .formatted(now, now + 1000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CORRIDOR_RECTANGLE"));
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"ic2","xMin":700,"yMin":700,"xMax":800,"yMax":800,
                                 "startAt":%d,"endAt":%d,"altitudeLower":0,"altitudeUpper":100,
                                 "priority":1,"dispatcherId":"ops-lead","requestId":"idem-c-2"}"""
                                .formatted(now, now + 1000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
    }
}
