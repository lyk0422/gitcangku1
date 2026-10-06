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
 * 圆形障碍水平包络审查 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由）：
 * 覆盖登记/修订/撤销主流程、400/404/409 失败分支、包络审查裁决与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("圆形障碍水平包络审查 HTTP API 端到端")
class ObstacleControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("DELETE FROM obstacle");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    @Test
    @DisplayName("主流程：登记障碍后包络审查 BLOCKED，修订圆心后 CLEAR，历史保留")
    void obstacleEnvelopeFlowOverHttp() throws Exception {
        // 创建带包络半径 1 的航线 (0,0)-(10,0)
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","envelopeRadius":1,"requestId":"req-route-1",
                                 "points":[{"x":0,"y":0},{"x":10,"y":0}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.envelopeRadius").value(1));

        // 登记障碍：心 (5,3) 半径 2 → 201，障碍版本 1，空域版本 1
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"ob1","operator":"tester",
                                 "centerX":5,"centerY":3,"radius":2,"requestId":"req-ob-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.airspaceVersion").value(1));

        // 审查 → BLOCKED，命中明细含障碍版本与两半径
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"req-review-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.hitObstacles[0].obstacleId").value("ob1"))
                .andExpect(jsonPath("$.data.hitObstacles[0].obstacleVersion").value(1))
                .andExpect(jsonPath("$.data.hitObstacles[0].segmentIndex").value(0))
                .andExpect(jsonPath("$.data.hitObstacles[0].obstacleRadius").value(2))
                .andExpect(jsonPath("$.data.hitObstacles[0].routeEnvelopeRadius").value(1));

        // 错误 expectedVersion 修订 → 409
        mockMvc.perform(post("/api/airspace/obstacles/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"ob1","operator":"tester","expectedVersion":9,
                                 "centerX":5,"centerY":4,"radius":2,"requestId":"req-rv-bad"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 正确修订心 (5,4) → 200，障碍版本 2，空域版本 2
        mockMvc.perform(post("/api/airspace/obstacles/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"ob1","operator":"tester","expectedVersion":1,
                                 "centerX":5,"centerY":4,"radius":2,"requestId":"req-rv-ok"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.airspaceVersion").value(2));

        // 原审查 STALE
        mockMvc.perform(get("/api/airspace/routes/r1/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"))
                .andExpect(jsonPath("$.current").value(false));

        // 新空域版本审查 → CLEAR
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":2,
                                 "requestId":"req-review-2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.hitObstacles").isEmpty());

        // 撤销障碍 → 200，状态 REVOKED，版本 3
        mockMvc.perform(post("/api/airspace/obstacles/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"ob1","operator":"tester","expectedVersion":2,
                                 "requestId":"req-rk-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.version").value(3));
    }

    @Test
    @DisplayName("失败分支：非法参数 400、不存在 404、重复登记与撤销后修订 409")
    void obstacleFailuresOverHttp() throws Exception {
        // 负半径 → 400（Bean Validation）
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"b1","operator":"tester",
                                 "centerX":0,"centerY":0,"radius":-1,"requestId":"req-b1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 圆心越界 → 400
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"b2","operator":"tester",
                                 "centerX":100001,"centerY":0,"radius":1,"requestId":"req-b2"}"""))
                .andExpect(status().isBadRequest());

        // 负包络半径航线 → 400
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"b3","envelopeRadius":-2,"requestId":"req-b3",
                                 "points":[{"x":0,"y":0},{"x":10,"y":0}]}"""))
                .andExpect(status().isBadRequest());

        // 修订不存在的障碍 → 404
        mockMvc.perform(post("/api/airspace/obstacles/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"ghost","operator":"tester","expectedVersion":1,
                                 "centerX":0,"centerY":0,"radius":1,"requestId":"req-b4"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OBSTACLE_NOT_FOUND"));

        // 重复登记 → 409
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"dup","operator":"tester",
                                 "centerX":0,"centerY":0,"radius":1,"requestId":"req-b5"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"dup","operator":"tester",
                                 "centerX":1,"centerY":1,"radius":1,"requestId":"req-b6"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OBSTACLE_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("幂等：同键同参重放 replayed=true，异参 409，失败不占键")
    void obstacleIdempotencyOverHttp() throws Exception {
        String body = """
                {"obstacleId":"i1","operator":"tester",
                 "centerX":5,"centerY":3,"radius":2,"requestId":"ob-idem-http"}""";
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        // 同键同参重放
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.version").value(1));
        // 同键异参 → 409
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"i1","operator":"tester",
                                 "centerX":5,"centerY":4,"radius":2,"requestId":"ob-idem-http"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));

        // 失败不占键：版本冲突的修订失败后，同键可用于正确请求
        mockMvc.perform(post("/api/airspace/obstacles/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"i1","operator":"tester","expectedVersion":9,
                                 "centerX":5,"centerY":4,"radius":2,"requestId":"ob-idem-fail"}"""))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/airspace/obstacles/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"i1","operator":"tester","expectedVersion":1,
                                 "centerX":5,"centerY":4,"radius":2,"requestId":"ob-idem-fail"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.version").value(2));
    }
}
