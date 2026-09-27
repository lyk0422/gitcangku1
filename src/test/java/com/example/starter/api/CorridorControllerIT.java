package com.example.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 应急走廊与占用 HTTP API 端到端（H2 + 真实 Spring MVC、校验、异常处理与事务）：
 * 覆盖让渡主流程、409/422/400/404 失败分支、幂等重放与只读查询。
 * 时钟固定在走廊窗口 [T0, T0+1h) 中点，使“当前有效走廊”判定确定性成立。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("应急走廊与占用 HTTP API 端到端")
class CorridorControllerIT {

    static final long T0 = 1_000_000_000_000L;
    static final long HOUR = 3_600_000L;
    static final long MIN = 60_000L;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.ofEpochMilli(T0 + 30 * MIN), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM occupancy_event");
        jdbc.update("DELETE FROM yield_record");
        jdbc.update("DELETE FROM occupancy");
        jdbc.update("DELETE FROM corridor");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private MvcResult postJson(String url, String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private String createRouteAndReview(String routeId, long airspaceVersion,
                                        boolean emergency) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"req-route-%s",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""
                                .formatted(routeId, routeId)))
                .andExpect(status().isCreated());
        MvcResult result = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":%d,
                                 "emergency":%s,"requestId":"req-review-%s"}"""
                                .formatted(routeId, airspaceVersion, emergency, routeId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();
    }

    private String corridorBody(String corridorId, String requestId,
                                long startUtc, long endUtc, int altMin, int altMax) {
        return """
                {"corridorId":"%s","priority":0,"xMin":40,"yMin":5,"xMax":60,"yMax":15,
                 "startUtc":%d,"endUtc":%d,"altMin":%d,"altMax":%d,
                 "reason":"应急医疗转运","createdBy":"dispatcher-01","requestId":"%s"}"""
                .formatted(corridorId, startUtc, endUtc, altMin, altMax, requestId);
    }

    @Test
    @DisplayName("主流程：占用先登记，走廊创建后让渡；明细与事件可查且不可变")
    void corridorYieldsExistingOccupancyAndHistoryIsQueryable() throws Exception {
        String routeId = "it-route-1";
        String reviewId = createRouteAndReview(routeId, 0L, false);

        // 常规占用先登记（yieldable=true）
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"it-occ-1","kind":"REGULAR","routeId":"%s",
                                 "reviewId":"%s","startUtc":%d,"endUtc":%d,
                                 "altMin":100,"altMax":200,"yieldable":true,
                                 "requestId":"req-occ-it-1"}"""
                                .formatted(routeId, reviewId, T0 - HOUR, T0 + 2 * HOUR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 建走廊 → 201，占用被让渡，返回实际值
        MvcResult corridorResult = postJson("/api/airspace/corridors",
                corridorBody("it-c-1", "req-c-1", T0, T0 + HOUR, 100, 200), 201);
        JsonNode corridorData = objectMapper.readTree(
                corridorResult.getResponse().getContentAsString()).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(1, corridorData.path("yieldedCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(reviewId,
                corridorData.path("yields").get(0).path("reviewId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(HOUR,
                corridorData.path("yields").get(0).path("overlapDurationMillis").asLong());

        // 占用查询：YIELDED 且带走廊标识
        mockMvc.perform(get("/api/airspace/occupancies/it-occ-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("YIELDED"))
                .andExpect(jsonPath("$.corridorId").value("it-c-1"));

        // 让渡历史查询：固化原审查版本（空域 0）与走廊版本（空域 1）
        mockMvc.perform(get("/api/airspace/yields").param("corridorId", "it-c-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].occupancyId").value("it-occ-1"))
                .andExpect(jsonPath("$[0].corridorVersion").value(1))
                .andExpect(jsonPath("$[0].airspaceVersion").value(0))
                .andExpect(jsonPath("$[0].reason").value("应急医疗转运"));

        // 事件流：CREATED, YIELDED，顺序固定（不适用的关联字段序列化为 null）
        mockMvc.perform(get("/api/airspace/occupancies/it-occ-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$[0].reviewId").value(reviewId))
                .andExpect(jsonPath("$[1].eventType").value("YIELDED"))
                .andExpect(jsonPath("$[1].corridorId").value("it-c-1"))
                .andExpect(jsonPath("$[1].reason").value("应急医疗转运"))
                .andExpect(jsonPath("$[1].reviewId").doesNotExist());
    }

    @Test
    @DisplayName("失败分支：不可让渡占用 422 且整次回滚；走廊冲突 409 带标识；非法窗口 400")
    void failureBranchesOverHttp() throws Exception {
        String routeId = "it-route-2";
        String reviewId = createRouteAndReview(routeId, 0L, false);

        // 不可让渡常规占用
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"it-occ-2","kind":"REGULAR","routeId":"%s",
                                 "reviewId":"%s","startUtc":%d,"endUtc":%d,
                                 "altMin":100,"altMax":200,"yieldable":false,
                                 "requestId":"req-occ-it-2"}"""
                                .formatted(routeId, reviewId, T0 + MIN, T0 + 2 * MIN)))
                .andExpect(status().isCreated());

        // 建走廊 → 422，响应带走廊冲突占用标识
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corridorBody("it-c-2", "req-c-2", T0, T0 + HOUR, 100, 200)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORRIDOR_OCCUPANCY_BLOCKED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("it-occ-2")));

        // 整次回滚：走廊不存在
        mockMvc.perform(get("/api/airspace/corridors/it-c-2"))
                .andExpect(status().isNotFound());

        // 非法时间窗 → 400
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corridorBody("it-c-bad", "req-c-bad",
                                T0 + HOUR, T0, 100, 200)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_WINDOW"));

        // 撤销不存在的走廊 → 404
        mockMvc.perform(post("/api/airspace/corridors/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"ghost","requestId":"req-revoke-ghost"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("走廊先建：常规占用 409 带标识，应急占用可登记；同优先级重叠 409")
    void corridorFirstThenRegularConflictEmergencyAllowed() throws Exception {
        // 建走廊
        postJson("/api/airspace/corridors",
                corridorBody("it-c-3", "req-c-3", T0, T0 + HOUR, 100, 200), 201);

        // 同优先级时空重叠走廊 → 409
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corridorBody("it-c-3b", "req-c-3b", T0, T0 + HOUR, 100, 200)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRIDOR_OVERLAP"));

        // 常规审查穿过走廊 → 409 带走廊标识
        String routeId = "it-route-3";
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"req-route-%s",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""
                                .formatted(routeId, routeId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":1,
                                 "requestId":"req-review-regular-3"}"""
                                .formatted(routeId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRIDOR_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("it-c-3")));

        // 应急审查 CLEAR（不能以走廊绕过禁区，这里无禁飞区）
        String emergencyReviewId = createRouteAndReview("it-route-3e", 1L, true);
        // 应急占用成功
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"it-occ-3e","kind":"EMERGENCY",
                                 "routeId":"it-route-3e","reviewId":"%s",
                                 "startUtc":%d,"endUtc":%d,"altMin":100,"altMax":200,
                                 "requestId":"req-occ-3e"}"""
                                .formatted(emergencyReviewId, T0 + 5 * MIN, T0 + 10 * MIN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("EMERGENCY"))
                .andExpect(jsonPath("$.data.yieldable").value(org.hamcrest.Matchers.nullValue()));

        // 应急占用命中禁飞区时审查必须 BLOCKED、占用 409 REVIEW_NOT_CLEAR
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"it-z-3","xMin":45,"yMin":8,"xMax":55,"yMax":12,
                                 "requestId":"req-zone-3"}"""))
                .andExpect(status().isCreated());
        String blockedRoute = "it-route-3b";
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"req-route-%s",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""
                                .formatted(blockedRoute, blockedRoute)))
                .andExpect(status().isCreated());
        MvcResult blockedReview = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":2,
                                 "emergency":true,"requestId":"req-review-blocked-3"}"""
                                .formatted(blockedRoute)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andReturn();
        String blockedReviewId = objectMapper.readTree(
                blockedReview.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"it-occ-3b","kind":"EMERGENCY",
                                 "routeId":"%s","reviewId":"%s",
                                 "startUtc":%d,"endUtc":%d,"altMin":100,"altMax":200,
                                 "requestId":"req-occ-3b"}"""
                                .formatted(blockedRoute, blockedReviewId,
                                        T0 + 5 * MIN, T0 + 10 * MIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_CLEAR"));
    }

    @Test
    @DisplayName("撤销只影响后续占用；让渡航线重新审查后显式重新申请")
    void revokeAndReapplyFlowOverHttp() throws Exception {
        String routeId = "it-route-4";
        String reviewId = createRouteAndReview(routeId, 0L, false);
        mockMvc.perform(post("/api/airspace/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"it-occ-4","kind":"REGULAR","routeId":"%s",
                                 "reviewId":"%s","startUtc":%d,"endUtc":%d,
                                 "altMin":100,"altMax":200,"yieldable":true,
                                 "requestId":"req-occ-4"}"""
                                .formatted(routeId, reviewId, T0 + MIN, T0 + 2 * MIN)))
                .andExpect(status().isCreated());
        postJson("/api/airspace/corridors",
                corridorBody("it-c-4", "req-c-4", T0, T0 + HOUR, 100, 200), 201);
        // 已让渡不能起飞
        mockMvc.perform(post("/api/airspace/departures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"it-occ-4","requestId":"req-dep-4"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OCCUPANCY_YIELDED"));

        // 撤销走廊 → 空域版本 2
        mockMvc.perform(post("/api/airspace/corridors/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"it-c-4","requestId":"req-revoke-4"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.airspaceVersion").value(2));
        // 原占用仍 YIELDED
        mockMvc.perform(get("/api/airspace/occupancies/it-occ-4"))
                .andExpect(jsonPath("$.status").value("YIELDED"));

        // 重新审查（空域版本 2）→ CLEAR
        MvcResult reReview = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":2,
                                 "requestId":"req-rereview-4"}"""
                                .formatted(routeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andReturn();
        String newReviewId = objectMapper.readTree(reReview.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 显式重新申请 → 新 ACTIVE 占用，关联原占用
        mockMvc.perform(post("/api/airspace/occupancies/reapply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"it-occ-4-new","parentOccupancyId":"it-occ-4",
                                 "reviewId":"%s","startUtc":%d,"endUtc":%d,
                                 "altMin":100,"altMax":200,"requestId":"req-reapply-4"}"""
                                .formatted(newReviewId, T0 + 3 * MIN, T0 + 4 * MIN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.parentOccupancyId").value("it-occ-4"));

        // 新占用起飞成功
        mockMvc.perform(post("/api/airspace/departures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"occupancyId":"it-occ-4-new","requestId":"req-dep-new-4"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.departed").value(true));
    }

    @Test
    @DisplayName("幂等：走廊同键同参重放 replayed=true，异参 409，失败不占键")
    void corridorIdempotencyOverHttp() throws Exception {
        String body = corridorBody("it-c-5", "req-c-5", T0, T0 + HOUR, 100, 200);
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键异参（高度窗不同）→ 409
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corridorBody("it-c-5", "req-c-5", T0, T0 + HOUR, 0, 50)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));

        // 失败（400 非法窗口）不占键：同键随后换合法参数成功
        String failKey = "req-c-fail";
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corridorBody("it-c-fail", failKey, T0 + HOUR, T0, 100, 200)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corridorBody("it-c-fail", failKey, T0, T0 + HOUR, 0, 50)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
    }
}
