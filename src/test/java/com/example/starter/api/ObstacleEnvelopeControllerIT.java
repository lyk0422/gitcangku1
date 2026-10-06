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
 * 水平包络审查 HTTP API 端到端（H2 + 真实 Spring MVC 路由、校验与事务）：
 * 覆盖题干合成用例、障碍版本 409、非法参数 400 与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("水平包络审查 HTTP API 端到端")
class ObstacleEnvelopeControllerIT {

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
        jdbc.update("DELETE FROM obstacle");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    @Test
    @DisplayName("合成用例：心(5,3)半径2+航线半径1 BLOCKED，心改(5,4)后 CLEAR")
    void syntheticFlowOverHttp() throws Exception {
        // 登记航线 (0,0)-(10,0)，包络半径 1
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":0},{"x":10,"y":0}],"envelopeRadius":1}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1));

        // 登记障碍 心(5,3) 半径2 → 版本 1，空域版本 1
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"o1","cx":5,"cy":3,"radius":2,
                                 "operator":"tester","requestId":"req-ob-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.airspaceVersion").value(1));

        // 审查 → BLOCKED，保存命中线段、障碍版本与包络值
        MvcResult blockedResult = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"req-review-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.envelopeRadius").value(1))
                .andExpect(jsonPath("$.data.envelopeHits[0].obstacleId").value("o1"))
                .andExpect(jsonPath("$.data.envelopeHits[0].obstacleVersion").value(1))
                .andExpect(jsonPath("$.data.envelopeHits[0].segmentIndex").value(0))
                .andExpect(jsonPath("$.data.envelopeHits[0].routeRadius").value(1))
                .andExpect(jsonPath("$.data.envelopeHits[0].obstacleRadius").value(2))
                .andReturn();
        String blockedReviewId = objectMapper.readTree(
                blockedResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 修订障碍心为 (5,4) → 障碍版本 2，空域版本 2
        mockMvc.perform(post("/api/airspace/obstacles/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"o1","expectedVersion":1,"cx":5,"cy":4,"radius":2,
                                 "operator":"tester","requestId":"req-ob-2"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.airspaceVersion").value(2));

        // 原审查 STALE；历史结论保留 BLOCKED
        mockMvc.perform(get("/api/airspace/routes/r1/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"))
                .andExpect(jsonPath("$.current").value(false));
        mockMvc.perform(get("/api/airspace/reviews/" + blockedReviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.envelopeHits[0].obstacleId").value("o1"));

        // 无其他阻断 → CLEAR
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":2,
                                 "requestId":"req-review-2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.envelopeHits").isEmpty());
    }

    @Test
    @DisplayName("障碍版本与参数错误：expectedVersion 409、负半径 400、撤销后不可改")
    void obstacleErrorsOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"e1","cx":5,"cy":3,"radius":2,
                                 "requestId":"req-e-1"}"""))
                .andExpect(status().isCreated());

        // expectedVersion 不匹配 → 409
        mockMvc.perform(post("/api/airspace/obstacles/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"e1","expectedVersion":9,"cx":5,"cy":4,"radius":2,
                                 "requestId":"req-e-2"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 负半径 → 400（Bean Validation）
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"e2","cx":5,"cy":3,"radius":-1,
                                 "requestId":"req-e-3"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 不存在的障碍 → 404
        mockMvc.perform(post("/api/airspace/obstacles/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"ghost","expectedVersion":1,
                                 "requestId":"req-e-4"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OBSTACLE_NOT_FOUND"));

        // 正确撤销 → 版本 2；撤销后旧版本不可改
        mockMvc.perform(post("/api/airspace/obstacles/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"e1","expectedVersion":1,
                                 "requestId":"req-e-5"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
        mockMvc.perform(post("/api/airspace/obstacles/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"e1","expectedVersion":2,"cx":5,"cy":5,"radius":2,
                                 "requestId":"req-e-6"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OBSTACLE_ALREADY_REVOKED"));
    }

    @Test
    @DisplayName("障碍幂等：同键同参重放、异参 409、失败不占键")
    void obstacleIdempotencyOverHttp() throws Exception {
        String body = """
                {"obstacleId":"i1","cx":5,"cy":3,"radius":2,"requestId":"ob-idem-1"}""";
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.version").value(1));
        // 同键异参 → 409
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"i1","cx":5,"cy":4,"radius":2,
                                 "requestId":"ob-idem-1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
        // 失败不占键：负半径 400 后同键可成功
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"i2","cx":5,"cy":3,"radius":-1,
                                 "requestId":"ob-idem-2"}"""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/airspace/obstacles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obstacleId":"i2","cx":5,"cy":3,"radius":2,
                                 "requestId":"ob-idem-2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
    }
}
