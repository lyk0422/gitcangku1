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
 * 限时禁飞窗口的 HTTP API 端到端测试（H2 + 真实 MVC/校验/事务）：
 * 覆盖窗口 JSON 收发、时空一致 BLOCKED/端点相接 CLEAR、非法窗口 400、
 * 仅窗口改期推进版本并使结论 STALE，以及同键不同窗口 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("限时窗口 HTTP API 端到端")
class TimeWindowControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    @Test
    @DisplayName("窗口交叠 BLOCKED，结果回传双方窗口；历史与当前查询均含窗口快照")
    void windowsOverlapBlockedOverHttp() throws Exception {
        // 航线窗口 [1000,3000)
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w1","requestId":"req-w-route",
                                 "window":{"startUtcMillis":1000,"endUtcMillis":3000},
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.window.startUtcMillis").value(1000))
                .andExpect(jsonPath("$.data.window.endUtcMillis").value(3000));

        // 区域窗口 [2000,4000)，空间相交
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"wz1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "window":{"startUtcMillis":2000,"endUtcMillis":4000},
                                 "requestId":"req-w-zone"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.airspaceVersion").value(1))
                .andExpect(jsonPath("$.data.window.startUtcMillis").value(2000));

        MvcResult blocked = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w1","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"req-w-review"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.hitZoneIds[0]").value("wz1"))
                .andExpect(jsonPath("$.data.routeWindow.startUtcMillis").value(1000))
                .andExpect(jsonPath("$.data.routeWindow.endUtcMillis").value(3000))
                .andExpect(jsonPath("$.data.hitZoneWindows[0].zoneId").value("wz1"))
                .andExpect(jsonPath("$.data.hitZoneWindows[0].window.startUtcMillis").value(2000))
                .andExpect(jsonPath("$.data.hitZoneWindows[0].window.endUtcMillis").value(4000))
                .andReturn();
        String reviewId = objectMapper.readTree(
                blocked.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 历史查询保留窗口快照
        mockMvc.perform(get("/api/airspace/reviews/" + reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.routeWindow.startUtcMillis").value(1000))
                .andExpect(jsonPath("$.hitZoneWindows[0].window.endUtcMillis").value(4000));
        // 当前查询同样携带快照
        mockMvc.perform(get("/api/airspace/routes/w1/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.current").value(true));
    }

    @Test
    @DisplayName("时间仅端点相接 → CLEAR（即使空间相交）")
    void endpointTouchIsClearOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w2","requestId":"req-w2-route",
                                 "window":{"startUtcMillis":1000,"endUtcMillis":2000},
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"wz2","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "window":{"startUtcMillis":2000,"endUtcMillis":3000},
                                 "requestId":"req-w2-zone"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w2","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"req-w2-review"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.hitZoneIds.length()").value(0));
    }

    @Test
    @DisplayName("缺省窗口为全时：请求不带 window 时回传 null 时刻")
    void omittedWindowIsAlwaysOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w3","requestId":"req-w3-route",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.window.startUtcMillis").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.window.endUtcMillis").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"wz3","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-w3-zone"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w3","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"req-w3-review"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"));
    }

    @Test
    @DisplayName("非法窗口 400：仅给起点、起止相等")
    void invalidWindowRejectedOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"bad1","xMin":0,"yMin":0,"xMax":10,"yMax":10,
                                 "window":{"startUtcMillis":1000},
                                 "requestId":"req-bad1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_WINDOW"));

        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"bad2","requestId":"req-bad2",
                                 "window":{"startUtcMillis":2000,"endUtcMillis":2000},
                                 "points":[{"x":0,"y":0},{"x":1,"y":1}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_WINDOW"));
    }

    @Test
    @DisplayName("仅窗口改期推进版本：旧结论 STALE，新版本审核按新窗口判定")
    void windowOnlyReplaceMakesStaleOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w4","requestId":"req-w4-route",
                                 "window":{"startUtcMillis":1000,"endUtcMillis":3000},
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"wz4","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "window":{"startUtcMillis":2000,"endUtcMillis":4000},
                                 "requestId":"req-w4-zone"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w4","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"req-w4-review"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"));

        // 几何不变，仅改期到 [5000,6000) → 版本 2
        mockMvc.perform(post("/api/airspace/routes/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w4","expectedVersion":1,
                                 "window":{"startUtcMillis":5000,"endUtcMillis":6000},
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}],
                                 "requestId":"req-w4-replace"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.window.startUtcMillis").value(5000));

        // 旧结论 STALE
        mockMvc.perform(get("/api/airspace/routes/w4/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"))
                .andExpect(jsonPath("$.current").value(false));

        // 新版本 + 原空域版本审核 → CLEAR（时间不相交）
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w4","routeVersion":2,"airspaceVersion":1,
                                 "requestId":"req-w4-review2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));
    }

    @Test
    @DisplayName("旧风格替换省略 window → 重置为全时；同键不同窗口 409")
    void legacyReplaceResetsWindowAndDifferentWindowConflicts() throws Exception {
        // 建限时航线并确认 BLOCKED
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w5","requestId":"req-w5-route",
                                 "window":{"startUtcMillis":1000,"endUtcMillis":3000},
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"wz5","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "window":{"startUtcMillis":1000,"endUtcMillis":3000},
                                 "requestId":"req-w5-zone"}"""))
                .andExpect(status().isCreated());

        // 省略 window 的旧风格替换 → 窗口重置全时，版本 2
        mockMvc.perform(post("/api/airspace/routes/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w5","expectedVersion":1,
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}],
                                 "requestId":"req-w5-replace"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.window.startUtcMillis").value(org.hamcrest.Matchers.nullValue()));

        // 全时航线与限时区域时间必相交 → BLOCKED
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"w5","routeVersion":2,"airspaceVersion":1,
                                 "requestId":"req-w5-review"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"));

        // 同键不同窗口 → 409
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"wz6","xMin":0,"yMin":0,"xMax":1,"yMax":1,
                                 "window":{"startUtcMillis":1,"endUtcMillis":2},
                                 "requestId":"req-w5-idem"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"wz6","xMin":0,"yMin":0,"xMax":1,"yMax":1,
                                 "window":{"startUtcMillis":1,"endUtcMillis":3},
                                 "requestId":"req-w5-idem"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
    }
}
