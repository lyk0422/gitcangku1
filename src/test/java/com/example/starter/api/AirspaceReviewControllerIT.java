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
 * API 端到端测试（H2 + 真实 Spring MVC 路由、校验、统一异常处理与事务）：
 * 覆盖建区/建航线/审核/查询主流程、400/404/409 失败分支与幂等重放标记。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("禁飞区审查 HTTP API 端到端")
class AirspaceReviewControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM reroute_evaluation_candidate");
        jdbc.update("DELETE FROM reroute_evaluation");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    @Test
    @DisplayName("主流程：建航线 CLEAR，建区后 BLOCKED，历史与当前查询语义正确")
    void fullFlowOverHttp() throws Exception {
        // 创建航线 → 201，版本 1
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.version").value(1));

        // 首次审核（空域版本 0）→ 201 CLEAR
        MvcResult clearResult = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.current").value(true))
                .andReturn();
        String clearReviewId = objectMapper.readTree(
                clearResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 创建封锁航线的禁飞区 → 201，空域版本 1
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.airspaceVersion").value(1));

        // 旧空域版本审核 → 409
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-stale"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 当前空域版本审核 → BLOCKED 且命中 z1
        MvcResult blockedResult = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"req-review-2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.hitZoneIds[0]").value("z1"))
                .andReturn();
        String blockedReviewId = objectMapper.readTree(
                blockedResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 历史查询：保留各自原结论
        mockMvc.perform(get("/api/airspace/reviews/" + clearReviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.current").doesNotExist());
        mockMvc.perform(get("/api/airspace/reviews/" + blockedReviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("BLOCKED"));

        // 最新审核为当前版本 → 当前查询返回 BLOCKED
        mockMvc.perform(get("/api/airspace/routes/r1/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.current").value(true));
    }

    @Test
    @DisplayName("STALE：航线替换后当前结论失效，旧 CLEAR 不被当作当前通过")
    void currentReviewGoesStaleOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"s1","requestId":"req-s-route",
                                 "points":[{"x":0,"y":0},{"x":10,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"s1","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-s-review"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));

        // 错误 expectedVersion → 409
        mockMvc.perform(post("/api/airspace/routes/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"s1","expectedVersion":9,
                                 "points":[{"x":0,"y":0},{"x":1,"y":1}],"requestId":"req-s-bad"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 正确替换 → 200，版本 2
        mockMvc.perform(post("/api/airspace/routes/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"s1","expectedVersion":1,
                                 "points":[{"x":0,"y":0},{"x":1,"y":1}],"requestId":"req-s-ok"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        // 当前结论 STALE
        mockMvc.perform(get("/api/airspace/routes/s1/current-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("STALE"))
                .andExpect(jsonPath("$.current").value(false));
    }

    @Test
    @DisplayName("幂等：同键同参重放 replayed=true，异参 409，失败不占键")
    void idempotencyOverHttp() throws Exception {
        String body = """
                {"zoneId":"i1","xMin":0,"yMin":0,"xMax":10,"yMax":10,
                 "requestId":"idem-1"}""";
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        // 同键同参重放
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键异参 → 409
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"i1","xMin":1,"yMin":1,"xMax":11,"yMax":11,
                                 "requestId":"idem-1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));

        // 失败不占键：非法矩形（退化）400
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"i2","xMin":5,"yMin":5,"xMax":5,"yMax":10,
                                 "requestId":"idem-fail"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ZONE_RECTANGLE"));
        // 同键随后可用于合法请求
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"i2","xMin":0,"yMin":0,"xMax":10,"yMax":10,
                                 "requestId":"idem-fail"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    @DisplayName("参数与资源错误：400 校验失败、404 资源不存在、撤销两次 409")
    void validationAndNotFoundOverHttp() throws Exception {
        // 坐标越界 → 400（Bean Validation）
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"v1","xMin":0,"yMin":0,"xMax":100001,"yMax":10,
                                 "requestId":"req-v1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 航点不足 → 400
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"v2","requestId":"req-v2",
                                 "points":[{"x":0,"y":0}]}"""))
                .andExpect(status().isBadRequest());

        // 查询不存在的审核 → 404
        mockMvc.perform(get("/api/airspace/reviews/rv_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));

        // 撤销不存在的区 → 404
        mockMvc.perform(post("/api/airspace/zones/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"ghost","requestId":"req-v3"}"""))
                .andExpect(status().isNotFound());

        // 撤销两次 → 第二次 409
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z2","xMin":0,"yMin":0,"xMax":10,"yMax":10,
                                 "requestId":"req-v4"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z2","requestId":"req-v5"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/airspace/zones/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z2","requestId":"req-v6"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ZONE_ALREADY_REVOKED"));
    }

    @Test
    @DisplayName("改航评估：首个 CLEAR 选中并替换航线，历史查询稳定，全部 BLOCKED 返回 422")
    void rerouteEvaluationOverHttp() throws Exception {
        // 建航线（版本 1）与一个封住 y=10 水平候选的禁飞区（空域版本 1）
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"e1","requestId":"req-e-route",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"ez1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-e-zone"}"""))
                .andExpect(status().isCreated());

        // 候选 1 穿过区域 BLOCKED，候选 2 CLEAR 被选中
        String evalBody = """
                {"evaluationKey":"eval-1","routeId":"e1","expectedVersion":1,
                 "airspaceVersion":1,
                 "candidates":[
                   [{"x":0,"y":10},{"x":100,"y":10}],
                   [{"x":0,"y":80},{"x":100,"y":80}]
                 ]}""";
        mockMvc.perform(post("/api/airspace/reroute-evaluations")
                        .contentType(MediaType.APPLICATION_JSON).content(evalBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.selectedIndex").value(2))
                .andExpect(jsonPath("$.data.routeVersion").value(1))
                .andExpect(jsonPath("$.data.newRouteVersion").value(2))
                .andExpect(jsonPath("$.data.airspaceVersion").value(1))
                .andExpect(jsonPath("$.data.candidates[0].conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.candidates[0].hitZoneIds[0]").value("ez1"))
                .andExpect(jsonPath("$.data.candidates[1].conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.candidates[1].points[0].y").value(80));

        // 历史查询：固化结论（与响应体一致）
        mockMvc.perform(get("/api/airspace/reroute-evaluations/eval-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedIndex").value(2))
                .andExpect(jsonPath("$.candidates[0].hitZoneIds[0]").value("ez1"))
                .andExpect(jsonPath("$.candidates[1].points[0].y").value(80));

        // 同键同参重放：replayed=true，且不再推进版本（newRouteVersion 仍为 2）
        mockMvc.perform(post("/api/airspace/reroute-evaluations")
                        .contentType(MediaType.APPLICATION_JSON).content(evalBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.newRouteVersion").value(2));

        // 查询不存在的评估 → 404
        mockMvc.perform(get("/api/airspace/reroute-evaluations/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVALUATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("改航评估：全部候选 BLOCKED 返回 422 且给出逐候选命中集合，航线不被替换")
    void rerouteAllBlockedReturns422OverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"e2","requestId":"req-e2-route",
                                 "points":[{"x":0,"y":0},{"x":100,"y":100}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"ez2","xMin":10,"yMin":10,"xMax":80,"yMax":80,
                                 "requestId":"req-e2-zone"}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/airspace/reroute-evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"eval-2","routeId":"e2","expectedVersion":1,
                                 "airspaceVersion":1,
                                 "candidates":[
                                   [{"x":0,"y":50},{"x":100,"y":50}],
                                   [{"x":40,"y":40},{"x":60,"y":60}]
                                 ]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALL_CANDIDATES_BLOCKED"))
                .andExpect(jsonPath("$.candidates[0].hitZoneIds[0]").value("ez2"))
                .andExpect(jsonPath("$.candidates[1].hitZoneIds[0]").value("ez2"));
    }

    @Test
    @DisplayName("改航评估：候选不足、候选点不足、候选完全相同均返回 400")
    void rerouteCandidateValidationOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"e3","requestId":"req-e3-route",
                                 "points":[{"x":0,"y":0},{"x":10,"y":10}]}"""))
                .andExpect(status().isCreated());

        // 仅 1 个候选（要求 2~10）→ 400
        mockMvc.perform(post("/api/airspace/reroute-evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"eval-3a","routeId":"e3","expectedVersion":1,
                                 "airspaceVersion":0,
                                 "candidates":[[{"x":0,"y":1},{"x":10,"y":1}]]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 候选只有 1 个点（要求 2~50）→ 400
        mockMvc.perform(post("/api/airspace/reroute-evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"eval-3b","routeId":"e3","expectedVersion":1,
                                 "airspaceVersion":0,
                                 "candidates":[[{"x":0,"y":1}],[{"x":2,"y":2},{"x":3,"y":3}]]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 候选完全相同 → 业务 400
        mockMvc.perform(post("/api/airspace/reroute-evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"eval-3c","routeId":"e3","expectedVersion":1,
                                 "airspaceVersion":0,
                                 "candidates":[[{"x":0,"y":1},{"x":10,"y":1}],
                                               [{"x":0,"y":1},{"x":10,"y":1}]]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CANDIDATE"));
    }
}
