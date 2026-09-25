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
 * 跑道关闭与航班审查 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由）：
 * 覆盖登记跑道/关闭窗口/航班、批量审查 200/422 语义、查询接口与错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("跑道关闭与航班审查 HTTP API 端到端")
class RunwayClosureControllerIT {

    /** 测试基准时刻：2026-01-01T00:00:00Z 的 epoch 毫秒。 */
    private static final long T0 = 1_767_225_600_000L;
    private static final long HOUR = 3_600_000L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM flight_review_item");
        jdbc.update("DELETE FROM flight_review");
        jdbc.update("DELETE FROM flight_risk");
        jdbc.update("DELETE FROM flight");
        jdbc.update("DELETE FROM runway_closure");
        jdbc.update("DELETE FROM runway");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private void createRunway(String runwayId) throws Exception {
        mockMvc.perform(post("/api/airspace/runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"%s","hourlyCapacity":10,"requestId":"req-rw-%s"}"""
                                .formatted(runwayId, runwayId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(0));
    }

    private void createRoute(String routeId) throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","requestId":"req-rt-%s",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""
                                .formatted(routeId, routeId)))
                .andExpect(status().isCreated());
    }

    private void registerFlight(String flightId, String routeType, String eventNo,
                                String depRunway, long depTime, String arrRunway, long arrTime)
            throws Exception {
        createRoute("route-of-" + flightId);
        String eventNoJson = eventNo == null ? "null" : "\"" + eventNo + "\"";
        mockMvc.perform(post("/api/airspace/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":"%s","routeId":"route-of-%s","routeType":"%s",
                                 "eventNo":%s,"depRunwayId":"%s","depTimeUtc":%d,
                                 "arrRunwayId":"%s","arrTimeUtc":%d,"requestId":"req-fl-%s"}"""
                                .formatted(flightId, flightId, routeType, eventNoJson,
                                        depRunway, depTime, arrRunway, arrTime, flightId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("主流程：登记关闭窗口 → NORMAL 审查 422 → 查询窗口与审查原因")
    void closureCausesNormalFlightRejectionOverHttp() throws Exception {
        createRunway("RWY-HTTP-1");
        // 登记关闭窗口 → 201，跑道版本 1，返回 closureKey 指纹
        MvcResult closure = mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"RWY-HTTP-1","expectedRunwayVersion":0,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":false,
                                 "operator":"op-http"}""".formatted(T0, T0 + HOUR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.runwayVersion").value(1))
                .andReturn();
        String closureKey = objectMapper.readTree(closure.getResponse().getContentAsString())
                .path("data").path("closureKey").asText();

        // 同参数重放 → 同 closureKey 重放原结果
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"RWY-HTTP-1","expectedRunwayVersion":0,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":false,
                                 "operator":"op-http"}""".formatted(T0, T0 + HOUR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.closureKey").value(closureKey));

        // 查询跑道窗口
        mockMvc.perform(get("/api/airspace/runways/RWY-HTTP-1/closures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.windows.length()").value(1))
                .andExpect(jsonPath("$.windows[0].allowEmergency").value(false));

        // NORMAL 航班起飞段命中窗口 → 批量审查 422，原因 RUNWAY_CLOSED
        registerFlight("F-HTTP-1", "NORMAL", null,
                "RWY-HTTP-1", T0 + 1000, "RWY-HTTP-1", T0 + 2 * HOUR);
        MvcResult review = mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightIds":["F-HTTP-1"],"requestId":"req-review-http-1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.approved").value(false))
                .andExpect(jsonPath("$.data.items[0].result").value("REJECTED"))
                .andExpect(jsonPath("$.data.items[0].reason").value("RUNWAY_CLOSED"))
                .andReturn();
        String reviewId = objectMapper.readTree(review.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 查询审查原因
        mockMvc.perform(get("/api/airspace/flight-reviews/" + reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.items[0].reason").value("RUNWAY_CLOSED"));
        mockMvc.perform(get("/api/airspace/flights/F-HTTP-1/review-reason"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("RUNWAY_CLOSED"));
        // 航班保持 PENDING，无半成品状态
        mockMvc.perform(get("/api/airspace/flights/F-HTTP-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flight.status").value("PENDING"))
                .andExpect(jsonPath("$.risks.length()").value(0));
    }

    @Test
    @DisplayName("批准航班被新窗口转为 RUNWAY_RISK，改航后重新审查 200")
    void riskFlightRerouteFlowOverHttp() throws Exception {
        createRunway("RWY-HTTP-2");
        createRunway("RWY-HTTP-3");
        registerFlight("F-HTTP-2", "NORMAL", null,
                "RWY-HTTP-2", T0 + 1000, "RWY-HTTP-2", T0 + 2 * HOUR);
        // 批量审查批准 → 200
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightIds":["F-HTTP-2"],"requestId":"req-review-http-2"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true));

        // 新关闭窗口命中 → 航班转 RUNWAY_RISK 并固化快照
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"RWY-HTTP-2","expectedRunwayVersion":0,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":true,
                                 "operator":"op-http"}""".formatted(T0, T0 + HOUR)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/airspace/flights/F-HTTP-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flight.status").value("RUNWAY_RISK"))
                .andExpect(jsonPath("$.risks.length()").value(1))
                .andExpect(jsonPath("$.risks[0].runwayId").value("RWY-HTTP-2"));

        // 改航到无窗口跑道 → 回到 PENDING
        mockMvc.perform(post("/api/airspace/flights/reroute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":"F-HTTP-2","depRunwayId":"RWY-HTTP-3",
                                 "depTimeUtc":%d,"arrRunwayId":"RWY-HTTP-3","arrTimeUtc":%d,
                                 "requestId":"req-reroute-http-2"}"""
                                .formatted(T0 + 1000, T0 + 2 * HOUR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        // 重新审查批准
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightIds":["F-HTTP-2"],"requestId":"req-review-http-3"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true));
        // 起飞 → DEPARTED
        mockMvc.perform(post("/api/airspace/flights/depart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":"F-HTTP-2","requestId":"req-depart-http-2"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEPARTED"));
    }

    @Test
    @DisplayName("失败分支：版本冲突 409、跑道不存在 404、校验失败 400")
    void failureBranchesOverHttp() throws Exception {
        createRunway("RWY-HTTP-4");
        // 携带错误跑道版本 → 409 RUNWAY_VERSION_CONFLICT
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"RWY-HTTP-4","expectedRunwayVersion":7,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":false,
                                 "operator":"op-http"}""".formatted(T0, T0 + HOUR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUNWAY_VERSION_CONFLICT"));
        // 跑道不存在 → 404
        mockMvc.perform(get("/api/airspace/runways/RWY-GHOST/closures"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUNWAY_NOT_FOUND"));
        // 非法窗口 → 400
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"RWY-HTTP-4","expectedRunwayVersion":0,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":false,
                                 "operator":"op-http"}""".formatted(T0 + HOUR, T0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CLOSURE_WINDOW"));
        // 缺字段 → 400 VALIDATION_FAILED
        mockMvc.perform(post("/api/airspace/runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"RWY-X","requestId":"req-x"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 航班不存在 → 404
        mockMvc.perform(get("/api/airspace/flights/F-GHOST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"));
    }

    @Test
    @DisplayName("EMERGENCY 例外：允许窗口 + 事件号通过；未附事件号 422")
    void emergencyExceptionOverHttp() throws Exception {
        createRunway("RWY-HTTP-5");
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"RWY-HTTP-5","expectedRunwayVersion":0,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":true,
                                 "operator":"op-http"}""".formatted(T0, T0 + HOUR)))
                .andExpect(status().isCreated());

        registerFlight("F-HTTP-EM1", "EMERGENCY", "EV-HTTP-1",
                "RWY-HTTP-5", T0 + 1000, "RWY-HTTP-5", T0 + 2 * HOUR);
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightIds":["F-HTTP-EM1"],"requestId":"req-review-em-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true));

        registerFlight("F-HTTP-EM2", "EMERGENCY", null,
                "RWY-HTTP-5", T0 + 2000, "RWY-HTTP-5", T0 + 2 * HOUR);
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightIds":["F-HTTP-EM2"],"requestId":"req-review-em-2"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.items[0].reason").value("MISSING_EVENT_NO"));
    }
}
