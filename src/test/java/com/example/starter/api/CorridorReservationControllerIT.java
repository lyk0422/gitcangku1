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
 * 走廊预约 API 端到端测试（H2 + 真实 Spring MVC 路由、校验、统一异常处理与事务）：
 * 覆盖走廊创建、审核前置预约、容量 429 占用数、取消释放、容量上调、
 * 占用/时段/探测/历史只读查询及幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("走廊预约 HTTP API 端到端")
class CorridorReservationControllerIT {

    private static final long T = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

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

    private String createClearReview() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        MvcResult result = mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();
    }

    @Test
    @DisplayName("主流程：建走廊→CLEAR预约→占用查询→满员429→取消释放→再预约→历史")
    void fullReservationFlowOverHttp() throws Exception {
        String reviewId = createClearReview();

        // 创建走廊，容量 1
        mockMvc.perform(post("/api/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "capacity":1,"requestId":"req-corridor-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.capacity").value(1));

        // 预约成功 201
        String reservationBody = """
                {"reservationKey":"k1","corridorId":"c1","startTime":%d,"endTime":%d,
                 "reviewId":"%s","requestId":"req-res-1"}""".formatted(T, T + 30 * MIN, reviewId);
        mockMvc.perform(post("/api/corridors/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(reservationBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 起点时刻占用 1
        mockMvc.perform(get("/api/corridors/c1/occupancy").param("at", String.valueOf(T)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.reservations[0].reservationKey").value("k1"));
        // 左闭右开：终点时刻占用 0
        mockMvc.perform(get("/api/corridors/c1/occupancy")
                        .param("at", String.valueOf(T + 30 * MIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        // 重叠预约 → 429，返回当前占用数
        String overlapping = """
                {"reservationKey":"k2","corridorId":"c1","startTime":%d,"endTime":%d,
                 "reviewId":"%s","requestId":"req-res-2"}""".formatted(
                T + 10 * MIN, T + 40 * MIN, reviewId);
        mockMvc.perform(post("/api/corridors/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(overlapping))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CORRIDOR_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.currentOccupancy").value(1))
                .andExpect(jsonPath("$.capacity").value(1));

        // 取消 → 立即释放容量
        mockMvc.perform(post("/api/corridors/reservations/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationKey":"k1","requestId":"req-cancel-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // 原 429 的预约现在成功
        mockMvc.perform(post("/api/corridors/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(overlapping))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 时段查询
        mockMvc.perform(get("/api/corridors/c1/reservations")
                        .param("from", String.valueOf(T))
                        .param("to", String.valueOf(T + 15 * MIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.reservations[0].reservationKey").value("k2"));

        // 探测：k2 时段已满 → available=false
        mockMvc.perform(get("/api/corridors/c1/probe")
                        .param("start", String.valueOf(T + 10 * MIN))
                        .param("end", String.valueOf(T + 20 * MIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.peakOccupancy").value(1));

        // 历史：含已取消 k1 与生效 k2
        mockMvc.perform(get("/api/corridors/c1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    @DisplayName("前置依赖：审核不存在/STALE/航线不相交返回 422")
    void reviewPrerequisitesReturn422OverHttp() throws Exception {
        String reviewId = createClearReview();
        mockMvc.perform(post("/api/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c2","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "capacity":2,"requestId":"req-corridor-2"}"""))
                .andExpect(status().isCreated());

        // 审核不存在 → 422
        mockMvc.perform(post("/api/corridors/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("""
                                {"reservationKey":"k-missing","corridorId":"c2",
                                 "startTime":%d,"endTime":%d,
                                 "reviewId":"rv_ghost","requestId":"req-rm"}""")
                                .formatted(T, T + 10 * MIN)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_REQUIRED"));

        // 空域变化导致审核 STALE → 422
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"zfar","xMin":-500,"yMin":-500,"xMax":-400,"yMax":-400,
                                 "requestId":"req-zone-far"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/corridors/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("""
                                {"reservationKey":"k-stale","corridorId":"c2",
                                 "startTime":%d,"endTime":%d,
                                 "reviewId":"%s","requestId":"req-rs"}""")
                                .formatted(T, T + 10 * MIN, reviewId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_STALE"));
    }

    @Test
    @DisplayName("容量只能上调：下调 409，上调后可容纳更多重叠预约")
    void capacityAdjustmentOverHttp() throws Exception {
        String reviewId = createClearReview();
        mockMvc.perform(post("/api/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c3","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "capacity":1,"requestId":"req-corridor-3"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/corridors/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("""
                                {"reservationKey":"ka","corridorId":"c3",
                                 "startTime":%d,"endTime":%d,
                                 "reviewId":"%s","requestId":"req-ra"}""")
                                .formatted(T, T + 30 * MIN, reviewId)))
                .andExpect(status().isCreated());

        // 下调到相同容量 → 409
        mockMvc.perform(post("/api/corridors/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorKey":"c3","newCapacity":1,"requestId":"req-cap-down"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_ONLY_INCREASABLE"));
        // 容量超范围 → 400
        mockMvc.perform(post("/api/corridors/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorKey":"c3","newCapacity":51,"requestId":"req-cap-bad"}"""))
                .andExpect(status().isBadRequest());
        // 上调到 2 → 200
        mockMvc.perform(post("/api/corridors/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorKey":"c3","newCapacity":2,"requestId":"req-cap-up"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").value(2));
        // 第二个重叠预约成功
        mockMvc.perform(post("/api/corridors/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("""
                                {"reservationKey":"kb","corridorId":"c3",
                                 "startTime":%d,"endTime":%d,
                                 "reviewId":"%s","requestId":"req-rb"}""")
                                .formatted(T + 5 * MIN, T + 35 * MIN, reviewId)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/corridors/c3/occupancy")
                        .param("at", String.valueOf(T + 10 * MIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.capacity").value(2));
    }

    @Test
    @DisplayName("幂等与参数错误：同键重放 replayed=true，非法时长 400")
    void idempotencyAndValidationOverHttp() throws Exception {
        String reviewId = createClearReview();
        mockMvc.perform(post("/api/corridors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridorId":"c4","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "capacity":3,"requestId":"req-corridor-4"}"""))
                .andExpect(status().isCreated());

        String body = ("""
                {"reservationKey":"ki","corridorId":"c4","startTime":%d,"endTime":%d,
                 "reviewId":"%s","requestId":"idem-res-1"}""").formatted(T, T + 20 * MIN, reviewId);
        mockMvc.perform(post("/api/corridors/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/corridors/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));

        // 时长超过 120 分钟 → 400
        mockMvc.perform(post("/api/corridors/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("""
                                {"reservationKey":"ktoolong","corridorId":"c4",
                                 "startTime":%d,"endTime":%d,
                                 "reviewId":"%s","requestId":"req-toolong"}""")
                                .formatted(T, T + 121 * MIN, reviewId)))
                .andExpect(status().isBadRequest());

        // 查询不存在的走廊 → 404
        mockMvc.perform(get("/api/corridors/ghost/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORRIDOR_NOT_FOUND"));
    }
}
