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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 豁免包与带核销飞行审核的 HTTP API 端到端测试（H2 + 真实 Spring MVC、校验、
 * 统一异常处理与事务）：覆盖签发、核销 CLEAR、BLOCKED 不扣额、幂等重放、
 * flightKey 异参 409 与只读余额/流水查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("豁免包核销 HTTP API 端到端")
class ExemptionControllerIT {

    static final long NOW = 1_700_000_000_000L;
    static final long FROM = NOW - 3_600_000L;
    static final long TO = NOW + 3_600_000L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM flight_review");
        jdbc.update("DELETE FROM permit_redeem");
        jdbc.update("DELETE FROM permit_item");
        jdbc.update("DELETE FROM permit");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    /** 建航线、建与航线相交的区域，返回区域创建生效版本。 */
    private long seedRouteAndZone() throws Exception {
        mockMvc.perform(post("/api/airspace/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"r1","requestId":"req-route-1",
                                 "points":[{"x":0,"y":10},{"x":100,"y":10}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"z1","xMin":40,"yMin":5,"xMax":60,"yMax":15,
                                 "requestId":"req-zone-1"}"""))
                .andExpect(status().isCreated());
        Long version = jdbc.queryForObject(
                "SELECT created_version FROM no_fly_zone WHERE zone_id = 'z1'", Long.class);
        return version == null ? -1L : version;
    }

    @Test
    @DisplayName("主流程：签发→命中核销 CLEAR 扣 1→只读余额/流水；无豁免时 BLOCKED 不扣")
    void issueRedeemAndQueryOverHttp() throws Exception {
        long regionVersion = seedRouteAndZone();

        // 签发含 2 次额度的豁免包 → 201
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"p1","routeId":"r1","routeVersion":1,
                                 "items":[{"regionKey":"z1","regionVersion":%d,
                                           "validFrom":%d,"validTo":%d,"quota":2}],
                                 "requestId":"req-permit-1"}""".formatted(regionVersion, FROM, TO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.permitKey").value("p1"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].remaining").value(2));

        // 飞行审核命中区域且豁免有效 → CLEAR，每项扣 1，快照含核销前后余额
        MvcResult clearResult = mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f1","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-flight-1"}""".formatted(NOW)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("CLEAR"))
                .andExpect(jsonPath("$.data.permitKey").value("p1"))
                .andExpect(jsonPath("$.data.hitRegionKeys[0]").value("z1"))
                .andExpect(jsonPath("$.data.redeems[0].regionKey").value("z1"))
                .andExpect(jsonPath("$.data.redeems[0].balanceBefore").value(2))
                .andExpect(jsonPath("$.data.redeems[0].balanceAfter").value(1))
                .andReturn();
        String reviewId = objectMapper.readTree(
                clearResult.getResponse().getContentAsString())
                .path("data").path("reviewId").asText();

        // 余额查询反映扣减；核销流水可查；审核快照可查
        mockMvc.perform(get("/api/airspace/permits/p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].remaining").value(1));
        mockMvc.perform(get("/api/airspace/permits/p1/redeems"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flightKey").value("f1"))
                .andExpect(jsonPath("$[0].balanceBefore").value(2))
                .andExpect(jsonPath("$[0].balanceAfter").value(1));
        mockMvc.perform(get("/api/airspace/flight-reviews/" + reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("CLEAR"));
        mockMvc.perform(get("/api/airspace/flights/f1/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 同 requestId 同参重放 → 不重复扣额，replayed=true
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f1","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-flight-1"}""".formatted(NOW)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.reviewId").value(reviewId));
        mockMvc.perform(get("/api/airspace/permits/p1"))
                .andExpect(jsonPath("$.items[0].remaining").value(1));

        // 同 flightKey 异参（reviewAt 不同）→ 409
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f1","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-flight-diff"}""".formatted(TO)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLIGHT_ALREADY_REVIEWED"));
    }

    @Test
    @DisplayName("无有效豁免时飞行审核 BLOCKED，返回缺陷项且额度不扣；校验失败 400；资源缺失 404")
    void blockedAndErrorBranchesOverHttp() throws Exception {
        long regionVersion = seedRouteAndZone();

        // 无豁免包 → BLOCKED + MISSING 缺陷，不产生任何流水
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f-block","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-flight-block"}""".formatted(NOW)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.defects[0].regionKey").value("z1"))
                .andExpect(jsonPath("$.data.defects[0].reason").value("MISSING"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM permit_redeem", Integer.class).intValue());

        // 过期豁免包 → BLOCKED + EXPIRED，仍不扣额（先发一个有效包建立额度便于断言余额不变）
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"p2","routeId":"r1","routeVersion":1,
                                 "items":[{"regionKey":"z1","regionVersion":%d,
                                           "validFrom":%d,"validTo":%d,"quota":3}],
                                 "requestId":"req-permit-2"}""".formatted(regionVersion, FROM - 10, FROM - 1)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f-expired","routeId":"r1","reviewAt":%d,
                                 "requestId":"req-flight-exp"}""".formatted(NOW)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conclusion").value("BLOCKED"))
                .andExpect(jsonPath("$.data.defects[0].reason").value("EXPIRED"));
        assertEquals(3, jdbc.queryForObject(
                "SELECT remaining FROM permit_item WHERE permit_id='p2' AND region_key='z1'",
                Integer.class).intValue());

        // 签发参数非法（额度超过 100）→ 400 校验错误
        mockMvc.perform(post("/api/airspace/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permitKey":"p3","routeId":"r1","routeVersion":1,
                                 "items":[{"regionKey":"z1","regionVersion":%d,
                                           "validFrom":%d,"validTo":%d,"quota":500}],
                                 "requestId":"req-permit-3"}""".formatted(regionVersion, FROM, TO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 查询不存在的豁免包 → 404
        mockMvc.perform(get("/api/airspace/permits/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERMIT_NOT_FOUND"));

        // 审核不存在的航线 → 404
        mockMvc.perform(post("/api/airspace/flight-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightKey":"f-noroute","routeId":"ghost","reviewAt":%d,
                                 "requestId":"req-flight-noroute"}""".formatted(NOW)))
                .andExpect(status().isNotFound());
    }
}
