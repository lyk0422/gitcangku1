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
 * 走廊时段预约 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由、校验、统一异常处理）：
 * 覆盖建走廊/审核/预约/占用查询/取消主流程与 400/404/409/422/429 失败分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("走廊时段预约 HTTP API 端到端")
class CorridorReservationControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM corridor_reservation");
        jdbc.update("DELETE FROM corridor");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private String newClearReviewId(String routeId) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"req-route-%s",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""
                                .formatted(routeId, routeId)))
                .andExpect(status().isCreated());
        MvcResult review = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-%s"}""".formatted(routeId, routeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andReturn();
        return objectMapper.readTree(review.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();
    }

    @Test
    @DisplayName("主流程：建走廊、CLEAR 审核、预约、占用查询、取消与历史")
    void reservationFullFlowOverHttp() throws Exception {
        // 创建走廊（容量 1）→ 201
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","xMin":0,"yMin":0,"xMax":100,"yMax":100,
                                 "capacity":1,"corridorKey":"ck-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.capacity").value(1));

        String reviewId = newClearReviewId("r1");

        // 创建预约 → 201 ACTIVE
        MvcResult created = mockMvc.perform(post("/api/airspace/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationKey":"rk-1","corridorId":"c1",
                                 "startTime":"2026-10-01T10:00:00Z","endTime":"2026-10-01T11:00:00Z",
                                 "reviewId":"%s"}""".formatted(reviewId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.reviewId").value(reviewId))
                .andReturn();
        String reservationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("reservationId").asText();

        // 同键同参重放 → 200/201 且 replayed=true，预约数不变
        mockMvc.perform(post("/api/airspace/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationKey":"rk-1","corridorId":"c1",
                                 "startTime":"2026-10-01T10:00:00Z","endTime":"2026-10-01T11:00:00Z",
                                 "reviewId":"%s"}""".formatted(reviewId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.reservationId").value(reservationId));

        // 占用查询：时段内占用 1
        mockMvc.perform(get("/api/airspace/corridors/c1/occupancy")
                        .param("at", "2026-10-01T10:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupancy").value(1))
                .andExpect(jsonPath("$.capacity").value(1))
                .andExpect(jsonPath("$.reservations[0].reservationId").value(reservationId));

        // 探测：重叠时段不可预约
        mockMvc.perform(get("/api/airspace/corridors/c1/availability")
                        .param("start", "2026-10-01T10:30:00Z")
                        .param("end", "2026-10-01T11:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.occupancy").value(1));

        // 容量已满：第二个重叠预约 → 429，返回当前占用数
        mockMvc.perform(post("/api/airspace/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationKey":"rk-2","corridorId":"c1",
                                 "startTime":"2026-10-01T10:30:00Z","endTime":"2026-10-01T11:30:00Z",
                                 "reviewId":"%s"}""".formatted(reviewId)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CORRIDOR_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.details.occupancy").value(1))
                .andExpect(jsonPath("$.details.capacity").value(1));

        // 取消 → 200 CANCELLED，占用归零
        mockMvc.perform(post("/api/airspace/reservations/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"%s","requestId":"req-cancel-1"}"""
                                .formatted(reservationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mockMvc.perform(get("/api/airspace/corridors/c1/occupancy")
                        .param("at", "2026-10-01T10:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupancy").value(0));

        // 历史保留：按时段查询可见已取消预约
        mockMvc.perform(get("/api/airspace/corridors/c1/reservations")
                        .param("from", "2026-10-01T00:00:00Z")
                        .param("to", "2026-10-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reservationId").value(reservationId))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
        mockMvc.perform(get("/api/airspace/reservations/" + reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("失败分支：审核 STALE 422、参数校验 400、容量下调 409、异参 409")
    void failureBranchesOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","xMin":0,"yMin":0,"xMax":100,"yMax":100,
                                 "capacity":1,"corridorKey":"ck-1"}"""))
                .andExpect(status().isCreated());
        String reviewId = newClearReviewId("r1");

        // 空域变化使审核 STALE → 预约 422 REVIEW_STALE
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":-50,"yMin":-50,"xMax":-40,"yMax":-40,
                                 "requestId":"req-zone-1"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationKey":"rk-stale","corridorId":"c1",
                                 "startTime":"2026-10-01T10:00:00Z","endTime":"2026-10-01T11:00:00Z",
                                 "reviewId":"%s"}""".formatted(reviewId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_STALE"));

        // 时长超过 120 分钟 → 400
        mockMvc.perform(post("/api/airspace/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationKey":"rk-long","corridorId":"c1",
                                 "startTime":"2026-10-01T10:00:00Z","endTime":"2026-10-01T13:00:00Z",
                                 "reviewId":"%s"}""".formatted(reviewId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));

        // 容量越界（Bean 校验）→ 400
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c2","xMin":0,"yMin":0,"xMax":10,"yMax":10,
                                 "capacity":51,"corridorKey":"ck-2"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 容量下调 → 409
        mockMvc.perform(post("/api/airspace/corridors/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","capacity":3,"corridorKey":"ck-up"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").value(3));
        mockMvc.perform(post("/api/airspace/corridors/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","capacity":2,"corridorKey":"ck-down"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_NOT_INCREASED"));

        // 同键异参 → 409
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","xMin":0,"yMin":0,"xMax":100,"yMax":100,
                                 "capacity":1,"corridorKey":"ck-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        mockMvc.perform(post("/api/airspace/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","xMin":0,"yMin":0,"xMax":50,"yMax":50,
                                 "capacity":1,"corridorKey":"ck-1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));

        // 查询不存在的走廊 → 404
        mockMvc.perform(get("/api/airspace/corridors/ghost/occupancy")
                        .param("at", "2026-10-01T10:00:00Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORRIDOR_NOT_FOUND"));
    }
}
