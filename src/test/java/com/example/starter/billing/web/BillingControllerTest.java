package com.example.starter.billing.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.billing.support.MutableClock;
import com.example.starter.support.ClockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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

/**
 * 经真实 HTTP 栈（MockMvc 驱动完整 DispatcherServlet/校验/异常映射）验证接口契约，
 * 数据落真实 H2（MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BillingControllerTest {

    private static final Instant START = Instant.parse("2026-10-01T18:00:00Z");
    private static final Instant END = Instant.parse("2026-10-01T19:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        ClockService clockService() {
            return new MutableClock();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ClockService clock;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM settlement WHERE offer_id LIKE 't-http-%'");
        jdbcTemplate.update("DELETE FROM meter_reading WHERE offer_id LIKE 't-http-%'");
        jdbcTemplate.update("DELETE FROM commitment WHERE offer_id LIKE 't-http-%'");
        jdbcTemplate.update("DELETE FROM demand_offer WHERE id LIKE 't-http-%'");
        ((MutableClock) clock).setNow(Instant.parse("2026-10-01T17:30:00Z"));
    }

    @Test
    @DisplayName("HTTP端到端：邀约-承诺-读数-结算，201返回奖励7kWh/560分；重放幂等；409/422/400契约正确")
    void fullChainOverHttp() throws Exception {
        String offerId = "t-http-" + System.nanoTime();

        // 发布邀约。
        mockMvc.perform(post("/api/offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "offerId", offerId, "startTime", START.toString(),
                                "endTime", END.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.offerId").value(offerId));

        // 承诺 10 kWh。
        mockMvc.perform(post("/api/offers/{offerId}/commitments", offerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", "user-001", "committedKwh", new BigDecimal("10"),
                                "requestId", "http-c-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.committedKwh").value(10.0));

        // 参数校验失败 → 400。
        mockMvc.perform(post("/api/offers/{offerId}/commitments", offerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 基线、实际读数。
        registerReading(offerId, "user-001", "BASELINE", "30", null);
        registerReading(offerId, "user-001", "ACTUAL", "23", null);

        // 时段未结束 → 409。
        mockMvc.perform(post("/api/offers/{offerId}/users/user-001/settlements", offerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "http-s-1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        ((MutableClock) clock).setNow(END);

        // 首次结算 → 201，7 kWh / 560 分。
        MvcResult settled = mockMvc.perform(post("/api/offers/{offerId}/users/user-001/settlements", offerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "http-s-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INITIAL"))
                .andExpect(jsonPath("$.rewardKwh").value(7.0))
                .andExpect(jsonPath("$.amountFen").value(560))
                .andReturn();
        long settlementId = objectMapper.readTree(settled.getResponse().getContentAsString())
                .get("settlementId").asLong();

        // 同参重放 → 200/201 同 ID，不重复入账。
        mockMvc.perform(post("/api/offers/{offerId}/users/user-001/settlements", offerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "http-s-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementId").value(settlementId));

        // 汇总查询。
        mockMvc.perform(get("/api/offers/{offerId}/users/user-001/summary", offerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committedKwh").value(10.0))
                .andExpect(jsonPath("$.baselineVersion").value(1))
                .andExpect(jsonPath("$.actualVersion").value(1))
                .andExpect(jsonPath("$.rewardKwh").value(7.0))
                .andExpect(jsonPath("$.netSettlementFen").value(560))
                .andExpect(jsonPath("$.settlements.length()").value(1));

        // 读数缺失场景：新用户只承诺不登记读数 → 422。
        mockMvc.perform(post("/api/offers/{offerId}/commitments", offerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", "user-002", "committedKwh", new BigDecimal("8"),
                                "requestId", "http-c-2"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/offers/{offerId}/users/user-002/settlements", offerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "http-s-2"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_ENTITY"));

        // 不存在的邀约 → 404。
        mockMvc.perform(get("/api/offers/no-such-offer/users/u/summary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("HTTP端到端：读数更正后重算返回负差额与旧新版本")
    void correctionOverHttp() throws Exception {
        String offerId = "t-http-adj-" + System.nanoTime();
        mockMvc.perform(post("/api/offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "offerId", offerId, "startTime", START.toString(),
                                "endTime", END.toString()))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/offers/{offerId}/commitments", offerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", "u9", "committedKwh", new BigDecimal("10"),
                                "requestId", "http-adj-c"))))
                .andExpect(status().isCreated());
        registerReading(offerId, "u9", "BASELINE", "30", null);
        registerReading(offerId, "u9", "ACTUAL", "23", null);
        ((MutableClock) clock).setNow(END);
        mockMvc.perform(post("/api/offers/{offerId}/users/u9/settlements", offerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "http-adj-s1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountFen").value(560));

        registerReading(offerId, "u9", "ACTUAL", "28", "表计数据迟到后更正");
        mockMvc.perform(post("/api/offers/{offerId}/users/u9/settlements", offerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "http-adj-s2"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.actualVersion").value(2))
                .andExpect(jsonPath("$.previousActualVersion").value(1))
                .andExpect(jsonPath("$.amountFen").value(-400))
                .andExpect(jsonPath("$.correctionReason").value("表计数据迟到后更正"));
        mockMvc.perform(get("/api/offers/{offerId}/users/u9/summary", offerId))
                .andExpect(jsonPath("$.netSettlementFen").value(160));
    }

    private JsonNode registerReading(String offerId, String userId, String kind, String kwh,
                                     String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("valueKwh", new BigDecimal(kwh));
        body.put("periodStart", START.toString());
        body.put("periodEnd", END.toString());
        if (reason != null) {
            body.put("correctionReason", reason);
        }
        MvcResult result = mockMvc.perform(
                        post("/api/offers/{offerId}/users/{userId}/readings", offerId, userId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
