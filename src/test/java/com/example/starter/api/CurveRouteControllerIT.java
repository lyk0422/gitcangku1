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
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 二次曲线航线 HTTP 端到端测试（H2 + 真实 Spring MVC/事务）：
 * 题干相切场景、控制点冻结快照、STALE、登记校验与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("二次曲线航线 HTTP API 端到端")
class CurveRouteControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_curve_segment");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    @Test
    @DisplayName("相切 BLOCKED，下界改 6 后 CLEAR；快照冻结 P0/P1/P2")
    void tangentBlockedThenLoweredRectangleClears() throws Exception {
        mockMvc.perform(post("/api/airspace/curve-routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"q1","operator":"alice","requestId":"q-req-1",
                                 "segments":[{"p0":{"x":0,"y":0},"p1":{"x":5,"y":10},
                                             "p2":{"x":10,"y":0}}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"zt","xMin":4,"yMin":5,"xMax":6,"yMax":6,
                                 "requestId":"q-zone-1"}"""))
                .andExpect(status().isCreated());

        MvcResult blocked = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"q1","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"q-review-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.hitZoneIds[0]").value("zt"))
                .andExpect(jsonPath("$.data.kind").value("QUADRATIC"))
                .andExpect(jsonPath("$.data.segmentsSnapshot[0].p1.y").value(10))
                .andExpect(jsonPath("$.data.pointsSnapshot").isArray())
                .andExpect(jsonPath("$.data.pointsSnapshot").isEmpty())
                .andReturn();
        String blockedId = blocked.getResponse().getContentAsString();

        // 撤销后新建下界为 6 的矩形（空域版本 3）→ CLEAR
        mockMvc.perform(post("/api/airspace/zones/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"zt","requestId":"q-revoke-1"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"zc","xMin":4,"yMin":6,"xMax":6,"yMax":7,
                                 "requestId":"q-zone-2"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"q1","routeVersion":1,"airspaceVersion":3,
                                 "requestId":"q-review-2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));

        // 历史查询保留 BLOCKED
        String reviewId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(blockedId).path("data").path("reviewId").asText();
        mockMvc.perform(get("/api/airspace/reviews/" + reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.segmentsSnapshot[0].p1.y").value(10));
    }

    @Test
    @DisplayName("校验：缺操作者 400；段不连续 400；超过 5 段 400")
    void validationFailures() throws Exception {
        // 缺 operator
        mockMvc.perform(post("/api/airspace/curve-routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"bad1","requestId":"b1",
                                 "segments":[{"p0":{"x":0,"y":0},"p1":{"x":5,"y":10},
                                             "p2":{"x":10,"y":0}}]}"""))
                .andExpect(status().isBadRequest());

        // 段不连续
        mockMvc.perform(post("/api/airspace/curve-routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"bad2","operator":"alice","requestId":"b2",
                                 "segments":[
                                   {"p0":{"x":0,"y":0},"p1":{"x":5,"y":10},"p2":{"x":10,"y":0}},
                                   {"p0":{"x":11,"y":0},"p1":{"x":15,"y":10},"p2":{"x":20,"y":0}}
                                 ]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURVE_NOT_CONTINUOUS"));

        // 6 段（Bean Validation）
        String six = """
                {"p0":{"x":0,"y":0},"p1":{"x":5,"y":10},"p2":{"x":10,"y":0}""";
        StringBuilder body = new StringBuilder(
                "{\"routeId\":\"bad3\",\"operator\":\"alice\",\"requestId\":\"b3\",\"segments\":[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(six);
        }
        body.append("]}");
        mockMvc.perform(post("/api/airspace/curve-routes")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("曲线替换后当前结论 STALE，幂等同键重放原结果")
    void replaceStaleAndIdempotentReplay() throws Exception {
        mockMvc.perform(post("/api/airspace/curve-routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"q2","operator":"alice","requestId":"q2-create",
                                 "segments":[{"p0":{"x":0,"y":0},"p1":{"x":5,"y":10},
                                             "p2":{"x":10,"y":0}}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"zt","xMin":4,"yMin":5,"xMax":6,"yMax":6,
                                 "requestId":"q2-zone"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"q2","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"q2-review"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"));

        // 错误 expectedVersion → 409
        mockMvc.perform(post("/api/airspace/curve-routes/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"q2","expectedVersion":9,"operator":"alice",
                                 "requestId":"q2-bad-replace",
                                 "segments":[{"p0":{"x":0,"y":0},"p1":{"x":5,"y":9},
                                             "p2":{"x":10,"y":0}}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 正确替换 → 200 版本 2
        mockMvc.perform(post("/api/airspace/curve-routes/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"q2","expectedVersion":1,"operator":"alice",
                                 "requestId":"q2-replace",
                                 "segments":[{"p0":{"x":0,"y":0},"p1":{"x":5,"y":9},
                                             "p2":{"x":10,"y":0}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(get("/api/airspace/routes/q2/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"));

        // 同键重放登记请求 → replayed=true，不重复落库
        mockMvc.perform(post("/api/airspace/curve-routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"q2","operator":"alice","requestId":"q2-create",
                                 "segments":[{"p0":{"x":0,"y":0},"p1":{"x":5,"y":10},
                                             "p2":{"x":10,"y":0}}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键改参 → 409
        mockMvc.perform(post("/api/airspace/curve-routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"q2","operator":"bob","requestId":"q2-create",
                                 "segments":[{"p0":{"x":0,"y":0},"p1":{"x":5,"y":10},
                                             "p2":{"x":10,"y":0}}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
    }
}
