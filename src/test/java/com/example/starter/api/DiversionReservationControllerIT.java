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
 * 备降机场预留 HTTP API 端到端测试（H2 + 真实 Spring MVC、校验、统一异常处理与事务）：
 * 覆盖机场创建、题干主流程（低优先级占用→高优先级替换→同级 409）、
 * 起飞保护、422/404/409 失败分支与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("备降机场预留 HTTP API 端到端")
class DiversionReservationControllerIT {

    private static final long T0 = 1_700_000_000_000L;
    private static final long T1 = T0 + 3_600_000L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM reservation_replacement");
        jdbc.update("DELETE FROM diversion_reservation");
        jdbc.update("DELETE FROM diversion_airport");
        jdbc.update("DELETE FROM request_dedup");
    }

    @Test
    @DisplayName("主流程：建机场容量1，L 占用，H 替换 L，同级 409，查询含占用与替换链")
    void reserveDisplaceAndQueryOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"A","capacity":1,"requestId":"req-airport-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.capacity").value(1));

        // 低优先级 L 占用
        MvcResult lowResult = mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson("route-L", 1, "req-low")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.displacedReservationIds").isArray())
                .andReturn();
        String lowId = jsonNode(lowResult).path("data").path("reservationId").asText();

        // 高优先级 H 成功并整体替换 L，一次写入替换关系
        MvcResult highResult = mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson("route-H", 10, "req-high")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.displacedReservationIds[0]").value(lowId))
                .andExpect(jsonPath("$.data.replacements[0].replacedReservationId").value(lowId))
                .andExpect(jsonPath("$.data.replacements[0].replacedRouteId").value("route-L"))
                .andExpect(jsonPath("$.data.replacements[0].replacementRouteId").value("route-H"))
                .andReturn();
        String highId = jsonNode(highResult).path("data").path("reservationId").asText();

        // 同级申请 409，原占用保留
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson("route-H2", 10, "req-high2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_CAPACITY_CONFLICT"));

        // 查询：当前占用仅 H（priority 10），不可变替换链一条
        mockMvc.perform(get("/api/airspace/diversion/airports/A/occupancies")
                        .param("from", String.valueOf(T0))
                        .param("to", String.valueOf(T1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupancies.length()").value(1))
                .andExpect(jsonPath("$.occupancies[0].reservationId").value(highId))
                .andExpect(jsonPath("$.occupancies[0].priority").value(10))
                .andExpect(jsonPath("$.replacementChain.length()").value(1))
                .andExpect(jsonPath("$.replacementChain[0].replacedReservationId").value(lowId))
                .andExpect(jsonPath("$.replacementChain[0].replacementReservationId").value(highId));
    }

    @Test
    @DisplayName("起飞后不可被挤出：H 申请 409，被挤出预留起飞 409")
    void departedReservationBlocksDisplacementOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"A","capacity":1,"requestId":"req-airport-2"}"""))
                .andExpect(status().isCreated());
        MvcResult lowResult = mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson("route-L", 1, "req-low2")))
                .andExpect(status().isCreated())
                .andReturn();
        String lowId = jsonNode(lowResult).path("data").path("reservationId").asText();

        // L 起飞
        mockMvc.perform(post("/api/airspace/diversion/reservations/depart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"%s","requestId":"req-depart"}""".formatted(lowId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.departed").value(true));

        // 高优先级无法挤出已起飞预留 → 409
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson("route-H", 10, "req-high-blocked")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTED_RESERVATION_BLOCKS"));

        // 重复起飞 → 409
        mockMvc.perform(post("/api/airspace/diversion/reservations/depart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"%s","requestId":"req-depart-again"}""".formatted(lowId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_DEPARTED"));
    }

    @Test
    @DisplayName("422：容量非法、窗口非法、机场不存在；404：预留不存在、查询机场不存在")
    void unprocessableAndNotFoundOverHttp() throws Exception {
        // 容量 0 → 422
        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"BAD","capacity":0,"requestId":"req-bad-cap"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AIRPORT_CAPACITY"));
        // Bean Validation：capacity 缺失 → 400
        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"BAD2","requestId":"req-null-cap"}"""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"A","capacity":1,"requestId":"req-airport-ok"}"""))
                .andExpect(status().isCreated());

        // 窗口 start>=end → 422
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"A","routeId":"r","priority":1,
                                 "windowStart":100,"windowEnd":100,"requestId":"req-bad-win"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));
        // 机场不存在 → 422
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"GHOST","routeId":"r","priority":1,
                                 "windowStart":%d,"windowEnd":%d,"requestId":"req-ghost"}"""
                                .formatted(T0, T1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AIRPORT_NOT_FOUND"));
        // 起飞不存在预留 → 404
        mockMvc.perform(post("/api/airspace/diversion/reservations/depart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"rsv_nope","requestId":"req-depart-nope"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        // 查询不存在机场 → 404
        mockMvc.perform(get("/api/airspace/diversion/airports/GHOST/occupancies")
                        .param("from", String.valueOf(T0))
                        .param("to", String.valueOf(T1)))
                .andExpect(status().isNotFound());
        // 查询时段非法 → 422
        mockMvc.perform(get("/api/airspace/diversion/airports/A/occupancies")
                        .param("from", String.valueOf(T1))
                        .param("to", String.valueOf(T0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));
    }

    @Test
    @DisplayName("幂等：同键同参重放首次替换结果，换窗口 409，409 拒绝不占键")
    void idempotencyOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"A","capacity":1,"requestId":"req-airport-3"}"""))
                .andExpect(status().isCreated());
        MvcResult lowResult = mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson("route-L", 1, "req-low3")))
                .andExpect(status().isCreated())
                .andReturn();
        String lowId = jsonNode(lowResult).path("data").path("reservationId").asText();

        String highBody = reservationJson("route-H", 10, "req-high-fixed");
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(highBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.displacedReservationIds[0]").value(lowId));
        // 同键同参重放
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(highBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.displacedReservationIds[0]").value(lowId));
        // 同键换窗口 → 409
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"A","routeId":"route-H","priority":10,
                                 "windowStart":%d,"windowEnd":%d,"requestId":"req-high-fixed"}"""
                                .formatted(T0 + 1, T1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
    }

    private static String reservationJson(String routeId, int priority, String requestId) {
        return """
                {"airportId":"A","routeId":"%s","priority":%d,
                 "windowStart":%d,"windowEnd":%d,"requestId":"%s"}"""
                .formatted(routeId, priority, T0, T1, requestId);
    }

    private com.fasterxml.jackson.databind.JsonNode jsonNode(MvcResult result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString());
    }
}
