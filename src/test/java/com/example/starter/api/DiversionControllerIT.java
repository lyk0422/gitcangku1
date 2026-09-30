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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 备降机场预留 API 端到端测试（H2 + 真实 Spring MVC 路由、校验、统一异常处理与事务）：
 * 覆盖机场注册、按优先级申请/挤出、同级 409、422 参数错误、起飞、占用与替换链查询、
 * 幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("备降机场预留 HTTP API 端到端")
class DiversionControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM diversion_reservation");
        jdbc.update("DELETE FROM diversion_airport");
        jdbc.update("DELETE FROM request_dedup");
    }

    @Test
    @DisplayName("主流程：低级占用→高级替换成功→同级409；查询展示占用与不可变替换链")
    void reserveDisplaceQueryOverHttp() throws Exception {
        // 注册机场 A，容量 1
        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"A","capacity":1,"requestId":"req-airport-a"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.capacity").value(1));

        // 低级 L 申请 [100,200)
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-low","routeId":"L","airportId":"A",
                                 "priority":1,"windowStart":100,"windowEnd":200}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.displaced").isArray())
                .andExpect(jsonPath("$.data.displaced.length()").value(0));

        // 高级 H 申请重叠窗口 [150,250)：成功，替换 L
        String highBody = mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-high","routeId":"H","airportId":"A",
                                 "priority":9,"windowStart":150,"windowEnd":250}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.displaced.length()").value(1))
                .andExpect(jsonPath("$.data.displaced[0].routeId").value("L"))
                .andExpect(jsonPath("$.data.displaced[0].replacedBy").isString())
                .andReturn().getResponse().getContentAsString();
        String highId = objectMapper.readTree(highBody).path("data").path("reservationId").asText();

        // 同级 H2 申请同窗：409
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-same","routeId":"H2","airportId":"A",
                                 "priority":9,"windowStart":150,"windowEnd":250}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_CONFLICT"));

        // 查询占用：L 为 DISPLACED 且携带替换链指向 H，H 为 RESERVED
        mockMvc.perform(get("/api/airspace/diversion/airports/A/occupancy")
                        .param("from", "0").param("to", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(1))
                .andExpect(jsonPath("$.occupancy.length()").value(2))
                .andExpect(jsonPath("$.occupancy[0].routeId").value("L"))
                .andExpect(jsonPath("$.occupancy[0].status").value("DISPLACED"))
                .andExpect(jsonPath("$.occupancy[0].replacedBy").value(highId))
                .andExpect(jsonPath("$.occupancy[0].chain.length()").value(1))
                .andExpect(jsonPath("$.occupancy[0].chain[0].targetReservationId").value(highId))
                .andExpect(jsonPath("$.occupancy[1].routeId").value("H"))
                .andExpect(jsonPath("$.occupancy[1].status").value("RESERVED"))
                .andExpect(jsonPath("$.occupancy[1].chain.length()").value(0));
    }

    @Test
    @DisplayName("422：容量非正、窗口退化、机场不存在")
    void unprocessableParametersOverHttp() throws Exception {
        // 容量 0 → 422
        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"bad","capacity":0,"requestId":"req-bad-cap"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CAPACITY"));

        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"A","capacity":1,"requestId":"req-airport-a2"}"""))
                .andExpect(status().isCreated());

        // 退化窗口 → 422
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-bad-window","routeId":"R","airportId":"A",
                                 "priority":1,"windowStart":200,"windowEnd":200}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));

        // 机场不存在 → 422
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-no-airport","routeId":"R","airportId":"ZZ",
                                 "priority":1,"windowStart":0,"windowEnd":100}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AIRPORT_NOT_FOUND"));

        // 查询非法时段 → 422
        mockMvc.perform(get("/api/airspace/diversion/airports/A/occupancy")
                        .param("from", "100").param("to", "50"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));
    }

    @Test
    @DisplayName("已起飞不可挤出：起飞后高优先级重叠409；重复起飞409；幂等重放挤出清单")
    void departureAndIdempotencyOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"A","capacity":1,"requestId":"req-airport-a3"}"""))
                .andExpect(status().isCreated());
        String lowBody = mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-low2","routeId":"L2","airportId":"A",
                                 "priority":1,"windowStart":100,"windowEnd":200}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String lowId = objectMapper.readTree(lowBody).path("data").path("reservationId").asText();

        // 起飞
        mockMvc.perform(post("/api/airspace/diversion/reservations/depart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"%s","requestId":"req-depart"}""".formatted(lowId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEPARTED"));

        // 高优先级重叠：409，已起飞不能被挤出
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-high2","routeId":"H3","airportId":"A",
                                 "priority":9,"windowStart":150,"windowEnd":250}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_CONFLICT"));

        // 重复起飞 → 409
        mockMvc.perform(post("/api/airspace/diversion/reservations/depart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"%s","requestId":"req-depart-again"}"""
                                .formatted(lowId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_ACTIVE"));

        // 起飞不存在的预留 → 404
        mockMvc.perform(post("/api/airspace/diversion/reservations/depart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"dr_missing","requestId":"req-depart-missing"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("幂等：同键同参重放 replayed=true 且挤出清单一致；同键异参409")
    void idempotentReplayOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/diversion/airports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airportId":"A","capacity":1,"requestId":"req-airport-a4"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-low3","routeId":"L3","airportId":"A",
                                 "priority":1,"windowStart":100,"windowEnd":200}"""))
                .andExpect(status().isCreated());

        String highJson = """
                {"requestId":"req-high3","routeId":"H4","airportId":"A",
                 "priority":9,"windowStart":150,"windowEnd":250}""";
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(highJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.displaced[0].routeId").value("L3"));
        // 同键同参重放
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(highJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.displaced.length()").value(1));
        // 同键异参（换窗口）→ 409
        mockMvc.perform(post("/api/airspace/diversion/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-high3","routeId":"H4","airportId":"A",
                                 "priority":9,"windowStart":160,"windowEnd":260}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
    }
}
