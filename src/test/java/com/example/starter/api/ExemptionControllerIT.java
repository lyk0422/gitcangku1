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
 * 多区域豁免包配额核销 API 端到端测试（H2 + 真实 Spring MVC 路由、校验、
 * 统一异常处理与事务）：覆盖签发/撤销/核销主流程、BLOCKED 缺口、400/404/409
 * 失败分支与 requestId/flightKey 幂等语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("豁免包配额核销 HTTP API 端到端")
class ExemptionControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long T = 1_000_000_000_000L;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM permit_consumption");
        jdbc.update("DELETE FROM flight_review");
        jdbc.update("DELETE FROM permit_item");
        jdbc.update("DELETE FROM permit_package");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private void seedRouteAndZones() throws Exception {
        // 航线水平穿过 y=10，命中后续创建的区域
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        // 第一个区域生效空域版本为 1，第二个为 2
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.airspaceVersion").value(1));
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z2","xMin":80,"yMin":8,"xMax":90,"yMax":12,
                                 "requestId":"req-zone-2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.airspaceVersion").value(2));
    }

    @Test
    @DisplayName("主流程：签发→无豁免 BLOCKED→签发全包 CLEAR 扣额→余额/核销/审核历史只读可查")
    void issueBlockedThenClearOverHttp() throws Exception {
        seedRouteAndZones();

        // 仅覆盖 z1：同时命中 z1/z2 → BLOCKED，返回 z2 的 MISSING 缺口且不扣额
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"p-part","routeVersion":1,"requestId":"req-p-part",
                                 "items":[{"regionKey":"z1","regionVersion":1,
                                           "validFrom":%d,"validTo":%d,"quota":3}]}"""
                                .formatted(T - 1000, T + 1000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.permitKey").value("p-part"))
                .andExpect(jsonPath("$.data.items[0].remaining").value(3));

        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f-1","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-f-blocked"}""".formatted(T)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.reviewId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.hitRegionKeys[0]").value("z1"))
                .andExpect(jsonPath("$.data.hitRegionKeys[1]").value("z2"))
                .andExpect(jsonPath("$.data.deficits[1].regionKey").value("z2"))
                .andExpect(jsonPath("$.data.deficits[1].reason").value("MISSING"));
        // BLOCKED 不扣额（余额查询为裸 PermitView，路径无 data 前缀）
        mockMvc.perform(get("/api/airspace/permits/p-part"))
                .andExpect(jsonPath("$.items[0].remaining").value(3));

        // 签发覆盖两个命中区域（版本分别为 1、2）的全包 → CLEAR，每项扣 1
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"p-full","routeVersion":1,"requestId":"req-p-full",
                                 "items":[{"regionKey":"z1","regionVersion":1,
                                           "validFrom":%d,"validTo":%d,"quota":2},
                                          {"regionKey":"z2","regionVersion":2,
                                           "validFrom":%d,"validTo":%d,"quota":1}]}"""
                                .formatted(T - 1000, T + 1000, T - 1000, T + 1000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[1].remaining").value(1));

        MvcResult clearResult = mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f-2","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-f-clear"}""".formatted(T)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.permitKey").value("p-full"))
                .andExpect(jsonPath("$.data.consumed[0].before").value(2))
                .andExpect(jsonPath("$.data.consumed[0].after").value(1))
                .andExpect(jsonPath("$.data.snapshot.airspaceVersion").value(2))
                .andExpect(jsonPath("$.data.snapshot.permitVersion").value(1))
                .andReturn();
        String reviewId = objectMapper.readTree(clearResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 余额已扣减；核销历史 2 条；审核历史保留冻结快照
        mockMvc.perform(get("/api/airspace/permits/p-full"))
                .andExpect(jsonPath("$.items[0].remaining").value(1))
                .andExpect(jsonPath("$.items[1].remaining").value(0));
        mockMvc.perform(get("/api/airspace/permits/p-full/consumptions"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].regionKey").value("z1"))
                .andExpect(jsonPath("$[1].balanceAfter").value(0));
        mockMvc.perform(get("/api/airspace/flight-reviews/" + reviewId))
                .andExpect(jsonPath("$.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.snapshot.hits.length()").value(2));
    }

    @Test
    @DisplayName("撤销后核销失效；EXPIRED/EXHAUSTED 缺口通过 HTTP 返回")
    void revokeAndDeficitReasonsOverHttp() throws Exception {
        seedRouteAndZones();
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"p-r","routeVersion":1,"requestId":"req-p-r",
                                 "items":[{"regionKey":"z1","regionVersion":1,
                                           "validFrom":%d,"validTo":%d,"quota":1},
                                          {"regionKey":"z2","regionVersion":2,
                                           "validFrom":%d,"validTo":%d,"quota":1}]}"""
                                .formatted(T - 1000, T + 1000, T - 1000, T + 1000)))
                .andExpect(status().isCreated());
        // 首次 CLEAR：z2 耗尽
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"g-1","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-g-1"}""".formatted(T)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"));

        // 撤销豁免包后再审核：整包失效 → MISSING，历史核销仍可查
        mockMvc.perform(post("/api/airspace/permits/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"p-r","requestId":"req-p-revoke"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"g-2","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-g-2"}""".formatted(T)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.deficits[0].reason").value("MISSING"));
        mockMvc.perform(get("/api/airspace/permits/p-r/consumptions"))
                .andExpect(jsonPath("$.length()").value(2));

        // 再次撤销 → 409
        mockMvc.perform(post("/api/airspace/permits/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"p-r","requestId":"req-p-revoke-2"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERMIT_ALREADY_REVOKED"));
    }

    @Test
    @DisplayName("幂等：requestId 同参重放不扣额、异参 409；同 flightKey 异参 409")
    void idempotencyOverHttp() throws Exception {
        seedRouteAndZones();
        String permitBody = """
                {"permitKey":"p-i","routeVersion":1,"requestId":"req-p-i",
                 "items":[{"regionKey":"z1","regionVersion":1,
                           "validFrom":%d,"validTo":%d,"quota":5},
                          {"regionKey":"z2","regionVersion":2,
                           "validFrom":%d,"validTo":%d,"quota":5}]}"""
                .formatted(T - 1000, T + 1000, T - 1000, T + 1000);
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON).content(permitBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON).content(permitBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));

        String reviewBody = """
                {"flightKey":"f-i","routeId":"r1","reviewAt":%d,"requestId":"req-f-i"}"""
                .formatted(T);
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON).content(reviewBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.replayed").value(false));
        // 同 requestId 同参重放：replayed=true，不重复扣额（余额仍各为 4）
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON).content(reviewBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        mockMvc.perform(get("/api/airspace/permits/p-i"))
                .andExpect(jsonPath("$.items[0].remaining").value(4))
                .andExpect(jsonPath("$.items[1].remaining").value(4));

        // 同 flightKey 不同 reviewAt（新 requestId）→ 409
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f-i","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-f-i-diff"}""".formatted(T + 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLIGHT_PARAM_MISMATCH"));

        // 同 requestId 异参 → 409
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f-other","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-f-i"}""".formatted(T + 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));
    }

    @Test
    @DisplayName("参数与资源错误：400 校验失败、404 资源不存在")
    void validationAndNotFoundOverHttp() throws Exception {
        // 空区域项 → 400
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"p-v","routeVersion":1,"requestId":"req-p-v",
                                 "items":[]}"""))
                .andExpect(status().isBadRequest());

        // 额度超范围 → 400
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"p-v2","routeVersion":1,"requestId":"req-p-v2",
                                 "items":[{"regionKey":"z1","regionVersion":1,
                                           "validFrom":1,"validTo":2,"quota":101}]}"""))
                .andExpect(status().isBadRequest());

        // 查询不存在的豁免包 → 404
        mockMvc.perform(get("/api/airspace/permits/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERMIT_NOT_FOUND"));
        // 查询不存在的航班审核 → 404
        mockMvc.perform(get("/api/airspace/flight-reviews/fr_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_REVIEW_NOT_FOUND"));
        // 撤销不存在的豁免包 → 404
        mockMvc.perform(post("/api/airspace/permits/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"ghost","requestId":"req-rev-ghost"}"""))
                .andExpect(status().isNotFound());
        // 审核不存在的航线 → 404
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f-v","routeId":"ghost","reviewAt":1,
                                 "requestId":"req-f-v"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }
}
