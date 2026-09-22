package com.example.starter.web;

import java.time.Duration;

import com.example.starter.testsupport.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST API 端到端测试（MockMvc + 隔离 H2）：验证 HTTP 状态码、错误体、
 * 幂等重放原样响应、参数校验与额度查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(classes = TestClockConfiguration.class)
class ExposureApiWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.example.starter.testsupport.MutableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM quota_account");
        jdbc.update("DELETE FROM campaign");
        clock.setInstant(TestClockConfiguration.START);
    }

    @Test
    void fullHappyPathOverHttp() throws Exception {
        mockMvc.perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-c1","campaignId":"cmp-1","dailyTotalCap":2,"visitorCap":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campaignId").value("cmp-1"))
                .andExpect(jsonPath("$.dailyTotalCap").value(2));

        MvcResult applyResult = mockMvc.perform(post("/api/exposures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-a1","campaignId":"cmp-1","visitorId":"visitor-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.utcDate").value("2026-09-22"))
                .andReturn();
        String reservationId = com.jayway.jsonpath.JsonPath
                .read(applyResult.getResponse().getContentAsString(), "$.reservationId");

        // 幂等重放：同键同参返回原 201 与同一预占。
        mockMvc.perform(post("/api/exposures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-a1","campaignId":"cmp-1","visitorId":"visitor-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value(reservationId));

        // 同键异参 409。
        mockMvc.perform(post("/api/exposures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-a1","campaignId":"cmp-1","visitorId":"visitor-2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 访客上限 1：第二个访客不同，但 visitor-1 再来一次触发访客 429。
        mockMvc.perform(post("/api/exposures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-a2","campaignId":"cmp-1","visitorId":"visitor-1"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // 确认。
        mockMvc.perform(post("/api/reservations/{id}/confirm", reservationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-f1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 已确认再取消 409。
        mockMvc.perform(post("/api/reservations/{id}/cancel", reservationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-x1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 明细与额度查询。
        mockMvc.perform(get("/api/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mockMvc.perform(get("/api/campaigns/cmp-1/quota")
                        .param("visitorId", "visitor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignQuota.held").value(1))
                .andExpect(jsonPath("$.campaignQuota.available").value(1))
                .andExpect(jsonPath("$.visitorQuotas[0].held").value(1));
    }

    @Test
    void expiredConfirmReturns409OverHttp() throws Exception {
        mockMvc.perform(post("/api/campaigns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"req-c2","campaignId":"cmp-2","dailyTotalCap":1,"visitorCap":1}
                        """)).andExpect(status().isCreated());

        MvcResult applyResult = mockMvc.perform(post("/api/exposures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-a3","campaignId":"cmp-2","visitorId":"visitor-9"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String reservationId = com.jayway.jsonpath.JsonPath
                .read(applyResult.getResponse().getContentAsString(), "$.reservationId");

        clock.advance(Duration.ofSeconds(60));

        mockMvc.perform(post("/api/reservations/{id}/confirm", reservationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-f2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(get("/api/reservations/{id}", reservationId))
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        mockMvc.perform(get("/api/campaigns/cmp-2/quota").param("visitorId", "visitor-9"))
                .andExpect(jsonPath("$.campaignQuota.held").value(0))
                .andExpect(jsonPath("$.visitorQuotas[0].held").value(0));
    }

    @Test
    void validationAndNotFoundAreMapped() throws Exception {
        // 额度超出 1~100000 → 400。
        mockMvc.perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-bad","campaignId":"cmp-bad","dailyTotalCap":0,"visitorCap":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 畸形 JSON → 400。
        mockMvc.perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // 未知公告申请曝光 → 404。
        mockMvc.perform(post("/api/exposures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-miss","campaignId":"nope","visitorId":"v1"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 未知预占 → 404。
        mockMvc.perform(get("/api/reservations/rv-nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
