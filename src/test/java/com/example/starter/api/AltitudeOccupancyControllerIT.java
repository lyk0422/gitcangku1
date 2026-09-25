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
 * 高度层容量与垂直分离 API 端到端测试（H2 + 真实 Spring MVC 路由）：
 * 覆盖高度带配置/修改、占用创建与 429 明细、取消、按时段占用与垂直分离明细查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("高度层容量 API 端到端")
class AltitudeOccupancyControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM altitude_occupancy");
        jdbc.update("DELETE FROM zone_altitude_band");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    @Test
    @DisplayName("主流程：带高度带建区、CLEAR 审查、占用 201、容量满 429 带明细、取消释放")
    void occupancyFlowOverHttp() throws Exception {
        // 创建带高度带的区域（容量 1）→ 201
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-1",
                                 "bands":[{"bandId":"b1","lowerM":100,"upperM":200,"capacity":1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.airspaceVersion").value(1));

        // 高度带配置查询
        mockMvc.perform(get("/api/airspace/zones/z1/bands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.bands[0].bandId").value("b1"))
                .andExpect(jsonPath("$.bands[0].capacity").value(1));

        // 上调容量 → 配置版本 2
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","expectedVersion":1,
                                 "capacityUpdates":[{"bandId":"b1","capacity":2}],
                                 "requestId":"req-mod-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configVersion").value(2));

        // 版本冲突 → 409
        mockMvc.perform(post("/api/airspace/zones/bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","expectedVersion":1,
                                 "capacityUpdates":[{"bandId":"b1","capacity":3}],
                                 "requestId":"req-mod-2"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 两条二维不相交、巡航高度落入带内的航线，时段重叠
        createRouteAndReview("r1", "req-route-1", "req-review-1");
        String review2 = createRouteAndReview("r2", "req-route-2", "req-review-2");

        // 两个占用均成功（容量已上调为 2）
        String occupancy1 = createOccupancy("z1", "b1", reviewIdOf("r1"), "req-occ-1", 201);
        createOccupancy("z1", "b1", review2, "req-occ-2", 201);

        // 第三个占用：容量满 → 429，明细含区域、高度带与占用数
        createRouteAndReview("r3", "req-route-3", "req-review-3");
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"z1","bandId":"b1",
                                 "requestId":"req-occ-3"}""".formatted(reviewIdOf("r3"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.details.zoneId").value("z1"))
                .andExpect(jsonPath("$.details.bandId").value("b1"))
                .andExpect(jsonPath("$.details.activeCount").value(2))
                .andExpect(jsonPath("$.details.capacity").value(2));

        // 取消第一个占用 → 容量释放，第三个占用可创建
        mockMvc.perform(post("/api/airspace/occupancies/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"%s","requestId":"req-cancel-1"}"""
                                .formatted(occupancy1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        createOccupancy("z1", "b1", reviewIdOf("r3"), "req-occ-3b", 201);

        // 按时段占用查询：含已取消历史
        mockMvc.perform(get("/api/airspace/zones/z1/occupancies")
                        .param("fromAt", "0").param("toAt", "10000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupancies.length()").value(3));

        // 垂直分离审查明细
        mockMvc.perform(get("/api/airspace/reviews/" + review2 + "/vertical-separation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cruiseAltitudeM").value(150));
    }

    @Test
    @DisplayName("失败分支：STALE 占用 422，BLOCKED 占用 409，占用幂等重放")
    void occupancyFailuresOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-z1",
                                 "bands":[{"bandId":"b1","lowerM":100,"upperM":200,"capacity":1}]}"""))
                .andExpect(status().isCreated());

        // 二维不相交、高度落入带内 → CLEAR
        String reviewId = createRouteAndReview("r1", "req-r1", "req-rv1");

        // 空域变化（新建区域）→ 关联审查 STALE → 占用 422
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z2","xMin":400,"yMin":5,"xMax":600,"yMax":15,
                                 "requestId":"req-z2"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"z1","bandId":"b1",
                                 "requestId":"req-o-stale"}""".formatted(reviewId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_STALE"));

        // 二维相交且高度落入带内 → BLOCKED；占用 409
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"rb","requestId":"req-rb",
                                 "cruiseAltitudeM":150,"startAt":1000,"endAt":2000,
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        MvcResult blocked = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"rb","routeVersion":1,"airspaceVersion":2,
                                 "requestId":"req-rvb"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andReturn();
        String blockedReviewId = objectMapper.readTree(
                blocked.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"z1","bandId":"b1",
                                 "requestId":"req-o-blocked"}""".formatted(blockedReviewId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_CLEAR"));

        // 当前版本 CLEAR 审查 → 占用 201；同键重放 replayed=true
        MvcResult clear = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":2,
                                 "requestId":"req-rv1b"}"""))
                .andExpect(status().isCreated())
                .andReturn();
        String clearReviewId = objectMapper.readTree(
                clear.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();
        String body = """
                {"reviewId":"%s","zoneId":"z1","bandId":"b1","requestId":"req-o-1"}"""
                .formatted(clearReviewId);
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.consumes").value(true));
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    /** 创建二维不相交、巡航高度 150、时段 [1000,2000) 的航线并完成 CLEAR 审查。 */
    private String createRouteAndReview(String routeId, String routeReqId, String reviewReqId)
            throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"%s",
                                 "cruiseAltitudeM":150,"startAt":1000,"endAt":2000,
                                 "points":[{"x":0,"y":1000},{"x":100,"y":1000}]}"""
                                .formatted(routeId, routeReqId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":%d,
                                 "requestId":"%s"}""".formatted(routeId, airspaceVersion(), reviewReqId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));
        return reviewIdOf(routeId);
    }

    private String reviewIdOf(String routeId) {
        return jdbc.queryForObject(
                "SELECT review_id FROM review WHERE route_id = ? "
                        + "ORDER BY created_at DESC, review_id DESC LIMIT 1",
                String.class, routeId);
    }

    private long airspaceVersion() {
        return jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
    }

    private String createOccupancy(String zoneId, String bandId, String reviewId,
                                   String requestId, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","zoneId":"%s","bandId":"%s",
                                 "requestId":"%s"}""".formatted(reviewId, zoneId, bandId, requestId)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("occupancyId").asText();
    }
}
