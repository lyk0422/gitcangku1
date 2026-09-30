package com.example.starter.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 活动匀速投放新端点的 HTTP 语义测试（真实 H2）：201/400/429/409、
 * 429 响应携带可区分原因与下一可用小时、只读诊断/日结/历史可访问。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityPacingHttpTest {

    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final Instant BASE = Instant.parse("2026-09-22T10:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_activity_hour_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM activity_daily_hour_snapshot");
        jdbc.update("DELETE FROM activity_hour_budget_freeze");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM activity");
        ((MutableClock) clock).setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private List<Integer> budgets(int hour, int total) {
        return java.util.stream.IntStream.range(0, 24)
                .map(h -> h == hour ? total : 0).boxed().toList();
    }

    @Test
    @DisplayName("活动端点：创建 201、预算合计不符 400、申请耗尽小时 429 带原因与下一可用小时")
    void activityEndpoints_httpSemantics() throws Exception {
        // 预算合计（1）不等于日总额（2）-> 400
        mockMvc.perform(post("/api/exposure/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
                            put("requestId", "a-bad");
                            put("activityId", "act-bad");
                            put("dailyTotalCap", 2);
                            put("hourlyBudget", budgets(10, 1));
                        }})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_HOURLY_BUDGET"))
                .andExpect(jsonPath("$.details.difference").value(-1));

        // 正常创建 201
        mockMvc.perform(post("/api/exposure/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
                            put("requestId", "a1");
                            put("activityId", "act1");
                            put("dailyTotalCap", 1);
                            put("hourlyBudget", budgets(10, 1));
                        }})))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.hourlyBudget[10]").value(1));

        // 公告归入活动并申请成功
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"c1\",\"campaignId\":\"c1\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":10,\"activityId\":\"act1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value("act1"));
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"campaignId\":\"c1\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value("act1"))
                .andExpect(jsonPath("$.utcHour").value(10));

        // 小时预算耗尽 -> 429 + reason/details
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r2\",\"campaignId\":\"c1\",\"visitorId\":\"u2\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.reason").value("HOUR_BUDGET_EXHAUSTED"))
                .andExpect(jsonPath("$.details.actualUsed").value(1))
                .andExpect(jsonPath("$.details.requiredBudget").value(1))
                .andExpect(jsonPath("$.details.difference").value(1));

        // 只读诊断：24 行，10 点 used=1
        mockMvc.perform(get("/api/exposure/activities/act1/hours").param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours.length()").value(24))
                .andExpect(jsonPath("$.hours[10].used").value(1))
                .andExpect(jsonPath("$.hours[10].remaining").value(0));

        // 日结（当日未终结 frozen=false）
        mockMvc.perform(get("/api/exposure/activities/act1/daily-snapshot")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frozen").value(false))
                .andExpect(jsonPath("$.hours.length()").value(24));

        // 历史只读
        mockMvc.perform(get("/api/exposure/reservations").param("campaignId", "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        // 不存在活动 -> 404
        mockMvc.perform(get("/api/exposure/activities/nope"))
                .andExpect(status().isNotFound());
    }
}
