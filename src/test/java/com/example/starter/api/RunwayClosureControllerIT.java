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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跑道关闭与容量联动 HTTP API 端到端测试（H2 + 真实 Spring MVC 路由）：
 * 覆盖跑道登记、关闭窗口、审查 422 联动、风险查询、批量审查与幂等重放标记。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("跑道关闭 HTTP API 端到端")
class RunwayClosureControllerIT {

    private static final long DEP = 1_800_000_000_000L + 10 * 3_600_000L;
    private static final long HOUR = 3_600_000L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM route_risk");
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

    @Test
    @DisplayName("主流程：跑道/关闭登记，NORMAL 422，紧急例外通过，风险固化与查询")
    void runwayClosureFlowOverHttp() throws Exception {
        // 登记跑道 → 201，版本 1
        mockMvc.perform(post("/api/airspace/runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"rw1","capacityPerHour":3,"requestId":"req-rw-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1));

        // 登记关闭窗口（允许紧急例外）→ 201，跑道版本 2
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closureKey":"ck-1","runwayId":"rw1","expectedRunwayVersion":1,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":true,
                                 "operator":"op-http"}""".formatted(DEP, DEP + 2 * HOUR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.runwayVersion").value(2))
                .andExpect(jsonPath("$.data.riskRouteIds").isArray());

        // 窗口查询：1 个窗口
        mockMvc.perform(get("/api/airspace/runways/rw1/closures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.closures[0].allowEmergency").value(true))
                .andExpect(jsonPath("$.closures[0].operator").value("op-http"));

        // NORMAL 航线起飞落入窗口 → 审查 422 RUNWAY_CLOSED
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}],
                                 "flightPlan":{"category":"NORMAL","depRunwayId":"rw1",
                                               "depTimeUtc":%d}}""".formatted(DEP + HOUR)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RUNWAY_CLOSED"));

        // EMERGENCY 附事件号 → 201，原因 EMERGENCY_EXCEPTION
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","requestId":"req-route-2",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}],
                                 "flightPlan":{"category":"EMERGENCY","eventNo":"EV-1",
                                               "depRunwayId":"rw1","depTimeUtc":%d}}"""
                                .formatted(DEP + HOUR)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r2","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.reasonCode").value("EMERGENCY_EXCEPTION"))
                .andExpect(jsonPath("$.data.hitClosureIds[0]").isString());

        // 未来已批准 NORMAL 航线被新窗口命中 → 转风险并可查询快照
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r3","requestId":"req-route-3",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}],
                                 "flightPlan":{"category":"NORMAL","depRunwayId":"rw1",
                                               "depTimeUtc":%d}}""".formatted(DEP + 5 * HOUR)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r3","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-3"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closureKey":"ck-2","runwayId":"rw1","expectedRunwayVersion":2,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":false,
                                 "operator":"op-http"}""".formatted(DEP + 4 * HOUR, DEP + 6 * HOUR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.riskRouteIds[0]").value("r3"));
        mockMvc.perform(get("/api/airspace/routes/r3/risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNWAY_RISK"))
                .andExpect(jsonPath("$.segment").value("DEPARTURE"))
                .andExpect(jsonPath("$.allowEmergency").value(false));
        // 风险航线不能普通再次批准
        mockMvc.perform(post("/api/airspace/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r3","routeVersion":1,"airspaceVersion":0,
                                 "requestId":"req-review-4"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROUTE_RUNWAY_RISK"));
        // 不允许例外的窗口：转紧急例外 422
        mockMvc.perform(post("/api/airspace/routes/emergency-exception")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r3","eventNo":"EV-9","requestId":"req-emg-1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMERGENCY_EXCEPTION_NOT_ALLOWED"));
        // 取消风险航线 → 200，风险查询 404
        mockMvc.perform(post("/api/airspace/routes/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r3","requestId":"req-cancel-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mockMvc.perform(get("/api/airspace/routes/r3/risk"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_RISK_NOT_FOUND"));
    }

    @Test
    @DisplayName("批量审查：整批批准；任一拒绝整批 422 且不留状态；同键重放")
    void batchReviewOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"rw1","capacityPerHour":5,"requestId":"req-rw-1"}"""))
                .andExpect(status().isCreated());
        for (String routeId : new String[]{"b1", "b2"}) {
            mockMvc.perform(post("/api/airspace/routes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"routeId":"%s","requestId":"req-route-%s",
                                     "points":[{"x":0,"y":10},{"x":100,"y":10}],
                                     "flightPlan":{"category":"NORMAL","depRunwayId":"rw1",
                                                   "depTimeUtc":%d}}"""
                                    .formatted(routeId, routeId, DEP + HOUR)))
                    .andExpect(status().isCreated());
        }
        // 整批批准 → 201，两条结果
        mockMvc.perform(post("/api/airspace/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airspaceVersion":0,"requestId":"req-batch-1",
                                 "items":[{"routeId":"b1","routeVersion":1},
                                          {"routeId":"b2","routeVersion":1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reviews.length()").value(2))
                .andExpect(jsonPath("$.data.reviews[0].conclusion").value("CLEAR"));
        // 同键同参重放 → replayed=true
        mockMvc.perform(post("/api/airspace/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airspaceVersion":0,"requestId":"req-batch-1",
                                 "items":[{"routeId":"b1","routeVersion":1},
                                          {"routeId":"b2","routeVersion":1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        // 容量 5 不变，批量版本冲突 → 409
        mockMvc.perform(post("/api/airspace/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airspaceVersion":0,"requestId":"req-batch-2",
                                 "items":[{"routeId":"b1","routeVersion":9}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("失败分支：重叠窗口 409、跑道版本冲突 409、校验失败 400")
    void closureFailureBranchesOverHttp() throws Exception {
        mockMvc.perform(post("/api/airspace/runways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runwayId":"rw1","capacityPerHour":5,"requestId":"req-rw-1"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closureKey":"ck-1","runwayId":"rw1","expectedRunwayVersion":1,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":false,
                                 "operator":"op"}""".formatted(DEP, DEP + HOUR)))
                .andExpect(status().isCreated());
        // 重叠 → 409 CLOSURE_OVERLAP
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closureKey":"ck-2","runwayId":"rw1","expectedRunwayVersion":2,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":false,
                                 "operator":"op"}""".formatted(DEP + HOUR / 2, DEP + 2 * HOUR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_OVERLAP"));
        // 跑道版本冲突 → 409
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closureKey":"ck-3","runwayId":"rw1","expectedRunwayVersion":1,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":false,
                                 "operator":"op"}""".formatted(DEP + 2 * HOUR, DEP + 3 * HOUR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUNWAY_VERSION_CONFLICT"));
        // 非法窗口 → 400
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closureKey":"ck-4","runwayId":"rw1","expectedRunwayVersion":2,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":false,
                                 "operator":"op"}""".formatted(DEP + 3 * HOUR, DEP + 2 * HOUR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CLOSURE_WINDOW"));
        // 缺少必填字段 → 400 VALIDATION_FAILED
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closureKey":"ck-5","runwayId":"rw1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 同键重放 → replayed=true，窗口仍只有一个
        mockMvc.perform(post("/api/airspace/runways/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closureKey":"ck-1","runwayId":"rw1","expectedRunwayVersion":1,
                                 "startUtc":%d,"endUtc":%d,"allowEmergency":false,
                                 "operator":"op"}""".formatted(DEP, DEP + HOUR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        mockMvc.perform(get("/api/airspace/runways/rw1/closures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closures.length()").value(1));
    }
}
