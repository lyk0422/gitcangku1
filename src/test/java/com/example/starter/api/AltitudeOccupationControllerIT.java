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
 * 高度带配置与高度层占用 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由）：
 * 覆盖配置/审查明细/占用/取消/时段查询主流程与 400/404/409/422/429 错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("高度层容量 HTTP API 端到端")
class AltitudeOccupationControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM altitude_occupation");
        jdbc.update("DELETE FROM altitude_band");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private void createRoute(String routeId, int cruise, long startUtc, long endUtc,
                             String requestId) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"%s",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}],
                                 "cruiseAltitude":%d,"startUtc":%d,"endUtc":%d}"""
                                .formatted(routeId, requestId, cruise, startUtc, endUtc)))
                .andExpect(status().isCreated());
    }

    private String review(String routeId, long airspaceVersion, String requestId)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":%d,
                                 "requestId":"%s"}"""
                                .formatted(routeId, airspaceVersion, requestId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();
    }

    private void setupManagedZone() throws Exception {
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"za","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-za"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"za","expectedVersion":1,"requestId":"req-band-za",
                                 "bands":[{"bandId":"b1","lowerAltitude":0,
                                           "upperAltitude":1000,"capacity":1}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.zoneVersion").value(2))
                .andExpect(jsonPath("$.data.airspaceVersion").value(2));
    }

    @Test
    @DisplayName("主流程：配置高度带→CLEAR 审查→占用→容量满 429→取消释放→时段查询")
    void occupationFullFlowOverHttp() throws Exception {
        setupManagedZone();
        createRoute("r1", 900, 1700000000000L, 1700003600000L, "req-route-1");
        createRoute("r2", 900, 1700000000000L, 1700003600000L, "req-route-2");
        String review1 = review("r1", 2L, "req-review-1");
        String review2 = review("r2", 2L, "req-review-2");

        // 垂直分离明细查询：管理空域不拦截，高度相交标注为 true
        mockMvc.perform(get("/api/airspace/reviews/" + review1 + "/vertical-separation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].zoneId").value("za"))
                .andExpect(jsonPath("$[0].blocked").value(false))
                .andExpect(jsonPath("$[0].bands[0].altitudeOverlap").value(true));

        // 区域高度带配置查询
        mockMvc.perform(get("/api/airspace/zones/za/bands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zoneVersion").value(2))
                .andExpect(jsonPath("$.bands[0].bandId").value("b1"))
                .andExpect(jsonPath("$.bands[0].capacity").value(1));

        // 创建占用 → 201 ACTIVE（容量 1）
        MvcResult occResult = mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"za","bandId":"b1",
                                 "requestId":"req-occ-1"}""".formatted(review1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.startUtc").value(1700000000000L))
                .andReturn();
        String occupationId = objectMapper.readTree(
                occResult.getResponse().getContentAsString())
                .path("data").path("occupationId").asText();

        // 同键重放 → 同一占用
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"za","bandId":"b1",
                                 "requestId":"req-occ-1"}""".formatted(review1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.occupationId").value(occupationId));

        // 容量 1 已满：第二条航线同时段占用 → 429 且给出区域、高度带与占用数
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"za","bandId":"b1",
                                 "requestId":"req-occ-2"}""".formatted(review2)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.zoneId").value("za"))
                .andExpect(jsonPath("$.bandId").value("b1"))
                .andExpect(jsonPath("$.activeCount").value(1))
                .andExpect(jsonPath("$.capacity").value(1));

        // 取消 → 立即释放容量
        mockMvc.perform(post("/api/airspace/occupations/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupationId":"%s","requestId":"req-cancel-1"}"""
                                .formatted(occupationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // 释放后第二条航线占用成功
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"za","bandId":"b1",
                                 "requestId":"req-occ-3"}""".formatted(review2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 按时段查询：含已取消历史共 2 条
        mockMvc.perform(get("/api/airspace/occupations")
                        .param("zoneId", "za")
                        .param("fromUtc", "1700000000000")
                        .param("toUtc", "1700003600000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        // 不相交时段查询为空
        mockMvc.perform(get("/api/airspace/occupations")
                        .param("fromUtc", "1700003600000")
                        .param("toUtc", "1700007200000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("错误语义：STALE 422、非 CLEAR 422、版本冲突 409、校验 400、不存在 404")
    void occupationErrorSemanticsOverHttp() throws Exception {
        setupManagedZone();
        createRoute("r1", 900, 1700000000000L, 1700003600000L, "req-route-1");
        String review1 = review("r1", 2L, "req-review-1");

        // 空域变化（新建区域）使审查 STALE → 创建占用 422
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"zb","xMin":-50,"yMin":-50,"xMax":-40,"yMax":-40,
                                 "requestId":"req-zone-zb"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"za","bandId":"b1",
                                 "requestId":"req-occ-stale"}""".formatted(review1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_STALE"));

        // 高度带配置版本冲突 → 409
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"za","expectedVersion":1,"requestId":"req-band-stale",
                                 "bands":[{"bandId":"b1","lowerAltitude":0,
                                           "upperAltitude":1000,"capacity":2}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 容量越界 → 400（Bean Validation）
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"za","expectedVersion":2,"requestId":"req-band-bad",
                                 "bands":[{"bandId":"b1","lowerAltitude":0,
                                           "upperAltitude":1000,"capacity":0}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 重叠高度带 → 400
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"za","expectedVersion":2,"requestId":"req-band-ovl",
                                 "bands":[{"bandId":"b1","lowerAltitude":0,
                                           "upperAltitude":1000,"capacity":1},
                                          {"bandId":"b2","lowerAltitude":500,
                                           "upperAltitude":1500,"capacity":1}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAND_OVERLAP"));

        // 占用/取消不存在的记录 → 404
        mockMvc.perform(post("/api/airspace/occupations/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupationId":"occ_ghost","requestId":"req-cancel-ghost"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OCCUPATION_NOT_FOUND"));
        mockMvc.perform(get("/api/airspace/zones/ghost/bands"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
    }

    @Test
    @DisplayName("非 CLEAR 审查创建占用 422；高度不相交 422")
    void occupationRequiresClearAndAltitudeOverlapOverHttp() throws Exception {
        // 纯禁飞区（无高度带）拦截航线 → BLOCKED
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"nfz","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-nfz"}"""))
                .andExpect(status().isCreated());
        createRoute("rb", 900, 1700000000000L, 1700003600000L, "req-route-b");
        MvcResult blockedResult = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"rb","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"req-review-b"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andReturn();
        String blockedReviewId = objectMapper.readTree(
                blockedResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();
        // BLOCKED 审查 → 422 REVIEW_NOT_CLEAR
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"nfz","bandId":"any",
                                 "requestId":"req-occ-blocked"}""".formatted(blockedReviewId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_CLEAR"));

        // 高度不相交：巡航 2000 不进入 [0,1000) → 422
        mockMvc.perform(post("/api/airspace/zones/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"nfz","requestId":"req-revoke-nfz"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"za","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-za2"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"za","expectedVersion":1,"requestId":"req-band-za2",
                                 "bands":[{"bandId":"b1","lowerAltitude":0,
                                           "upperAltitude":1000,"capacity":5}]}"""))
                .andExpect(status().isOk());
        createRoute("rh", 2000, 1700000000000L, 1700003600000L, "req-route-h");
        String highReview = review("rh", 4L, "req-review-h");
        mockMvc.perform(post("/api/airspace/occupations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"za","bandId":"b1",
                                 "requestId":"req-occ-high"}""".formatted(highReview)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALTITUDE_NOT_OVERLAPPING"));
    }
}
