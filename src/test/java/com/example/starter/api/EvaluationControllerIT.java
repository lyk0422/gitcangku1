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
 * 改航候选评估 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由、校验、统一异常处理）：
 * 覆盖主流程选中替换、候选集合 400 校验、全部 BLOCKED 的 422 明细、
 * 409 版本冲突、幂等重放标记与历史查询稳定。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("改航候选评估 HTTP API 端到端")
class EvaluationControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM evaluation_candidate");
        jdbc.update("DELETE FROM evaluation");
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
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}
                                """.formatted(routeId, requestId)))
                .andExpect(status().isCreated());
    }

    private void createZone(String zoneId, String requestId) throws Exception {
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"%s","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"%s"}
                                """.formatted(zoneId, requestId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("主流程：首个 CLEAR 候选被选中并替换航线，历史查询稳定")
    void evaluationSelectsFirstClearCandidate() throws Exception {
        createRoute("hr1", "req-route-1");
        createZone("hz1", "req-zone-1");

        MvcResult result = mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-http-1","routeId":"hr1",
                                 "expectedVersion":1,"airspaceVersion":1,
                                 "candidates":[
                                   [{"x":0,"y":10},{"x":100,"y":10}],
                                   [{"x":0,"y":30},{"x":100,"y":30}]]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.selectedIndex").value(1))
                .andExpect(jsonPath("$.data.newRouteVersion").value(2))
                .andExpect(jsonPath("$.data.airspaceVersion").value(1))
                .andExpect(jsonPath("$.data.candidates[0].conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.candidates[0].hitZoneIds[0]").value("hz1"))
                .andExpect(jsonPath("$.data.candidates[1].conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.selectedPoints[0].y").value(30))
                .andReturn();
        String evaluationId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("evaluationId").asText();

        // 同键同参重放：返回首次快照并带 replayed 标记
        mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-http-1","routeId":"hr1",
                                 "expectedVersion":1,"airspaceVersion":1,
                                 "candidates":[
                                   [{"x":0,"y":10},{"x":100,"y":10}],
                                   [{"x":0,"y":30},{"x":100,"y":30}]]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.evaluationId").value(evaluationId));

        // 历史查询：记录不可变
        mockMvc.perform(get("/api/airspace/evaluations/" + evaluationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedIndex").value(1))
                .andExpect(jsonPath("$.routeVersion").value(1))
                .andExpect(jsonPath("$.newRouteVersion").value(2))
                .andExpect(jsonPath("$.candidates[0].hitZoneIds[0]").value("hz1"));

        // 同键换序候选 → 409
        mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-http-1","routeId":"hr1",
                                 "expectedVersion":1,"airspaceVersion":1,
                                 "candidates":[
                                   [{"x":0,"y":30},{"x":100,"y":30}],
                                   [{"x":0,"y":10},{"x":100,"y":10}]]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
    }

    @Test
    @DisplayName("候选集合校验：数量越界、候选点列过短、坐标越界、候选重复均 400")
    void candidateSetValidationFailures() throws Exception {
        createRoute("hv1", "req-route-2");
        // 仅 1 个候选
        mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-bad-1","routeId":"hv1",
                                 "expectedVersion":1,"airspaceVersion":0,
                                 "candidates":[[{"x":0,"y":30},{"x":100,"y":30}]]}
                                """))
                .andExpect(status().isBadRequest());
        // 候选点列只有 1 个点
        mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-bad-2","routeId":"hv1",
                                 "expectedVersion":1,"airspaceVersion":0,
                                 "candidates":[
                                   [{"x":0,"y":30}],
                                   [{"x":0,"y":40},{"x":100,"y":40}]]}
                                """))
                .andExpect(status().isBadRequest());
        // 坐标超出 [-100000,100000]
        mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-bad-3","routeId":"hv1",
                                 "expectedVersion":1,"airspaceVersion":0,
                                 "candidates":[
                                   [{"x":0,"y":30},{"x":100001,"y":30}],
                                   [{"x":0,"y":40},{"x":100,"y":40}]]}
                                """))
                .andExpect(status().isBadRequest());
        // 候选完全相同 → 400 DUPLICATE_CANDIDATE
        mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-bad-4","routeId":"hv1",
                                 "expectedVersion":1,"airspaceVersion":0,
                                 "candidates":[
                                   [{"x":0,"y":30},{"x":100,"y":30}],
                                   [{"x":0,"y":30},{"x":100,"y":30}]]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CANDIDATE"));
        // 全部失败不占键、不写评估记录
        Integer evalRows = jdbc.queryForObject("SELECT COUNT(*) FROM evaluation", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, evalRows);
    }

    @Test
    @DisplayName("全部候选 BLOCKED 返回 422 及逐候选命中集合，不写入航线")
    void allBlockedReturns422WithPerCandidateHits() throws Exception {
        createRoute("hb1", "req-route-3");
        createZone("hzb", "req-zone-3");

        mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-422-1","routeId":"hb1",
                                 "expectedVersion":1,"airspaceVersion":1,
                                 "candidates":[
                                   [{"x":0,"y":10},{"x":100,"y":10}],
                                   [{"x":45,"y":0},{"x":55,"y":20}]]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALL_CANDIDATES_BLOCKED"))
                .andExpect(jsonPath("$.details[0].hitZoneIds[0]").value("hzb"))
                .andExpect(jsonPath("$.details[1].hitZoneIds[0]").value("hzb"));

        // 航线未被替换
        Integer version = jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'hb1'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, version);
    }

    @Test
    @DisplayName("版本不符 409 且不写入；历史评估查询 404")
    void versionConflictAndMissingEvaluation() throws Exception {
        createRoute("hv9", "req-route-4");
        createZone("hz9", "req-zone-4");

        // 空域版本不符 → 409
        mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-409-1","routeId":"hv9",
                                 "expectedVersion":1,"airspaceVersion":0,
                                 "candidates":[
                                   [{"x":0,"y":30},{"x":100,"y":30}],
                                   [{"x":0,"y":40},{"x":100,"y":40}]]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 航线版本不符 → 409
        mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-409-2","routeId":"hv9",
                                 "expectedVersion":9,"airspaceVersion":1,
                                 "candidates":[
                                   [{"x":0,"y":30},{"x":100,"y":30}],
                                   [{"x":0,"y":40},{"x":100,"y":40}]]}
                                """))
                .andExpect(status().isConflict());
        // 航线不存在 → 404
        mockMvc.perform(post("/api/airspace/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationKey":"ek-404-1","routeId":"ghost",
                                 "expectedVersion":1,"airspaceVersion":1,
                                 "candidates":[
                                   [{"x":0,"y":30},{"x":100,"y":30}],
                                   [{"x":0,"y":40},{"x":100,"y":40}]]}
                                """))
                .andExpect(status().isNotFound());
        // 评估记录不存在 → 404
        mockMvc.perform(get("/api/airspace/evaluations/ev_ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVALUATION_NOT_FOUND"));
        // 航线保持版本 1，无评估记录
        Integer version = jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'hv9'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, version);
        Integer evalRows = jdbc.queryForObject("SELECT COUNT(*) FROM evaluation", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, evalRows);
    }
}
