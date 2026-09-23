package com.example.starter.exposure.budget;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预算转移真实 HTTP 入口测试：JSON 绑定、Bean 校验（400）、创建 201、预览/激活/证据主流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BudgetHttpApiTest {

    /** 固定时钟，始终早于投放窗口起点。 */
    static class FixedClock extends Clock {
        private final Instant fixed = Instant.parse("2026-09-22T10:00:00Z");

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
            return fixed;
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return new FixedClock();
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;

    private final long windowStart = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli();
    private final long windowEnd = Instant.parse("2026-09-26T00:00:00Z").toEpochMilli();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM budget_transfer_snapshot");
        jdbc.update("DELETE FROM budget_transfer");
        jdbc.update("DELETE FROM budget_reservation");
        jdbc.update("DELETE FROM budget_campaign");
        jdbc.update("DELETE FROM idempotency_record");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("HTTP：创建活动 201、预览 200、激活 201、证据 200；明细不足 2 条 400")
    void httpEndpoints_fullFlow_andValidation400() throws Exception {
        mockMvc.perform(post("/api/budget/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-ca\",\"campaignId\":\"A\",\"tenantId\":\"t1\","
                                + "\"windowStartUtc\":" + windowStart + ",\"windowEndUtc\":" + windowEnd
                                + ",\"audienceRule\":\"R1\",\"budget\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campaignId").value("A"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/budget/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-cb\",\"campaignId\":\"B\",\"tenantId\":\"t1\","
                                + "\"windowStartUtc\":" + windowStart + ",\"windowEndUtc\":" + windowEnd
                                + ",\"audienceRule\":\"R1\",\"budget\":100}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/budget/transfers/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"details\":["
                                + "{\"sourceCampaignId\":\"A\",\"targetCampaignId\":\"B\","
                                + "\"amount\":40,\"sourceExpectedVersion\":1,\"targetExpectedVersion\":1},"
                                + "{\"sourceCampaignId\":\"B\",\"targetCampaignId\":\"A\","
                                + "\"amount\":10,\"sourceExpectedVersion\":1,\"targetExpectedVersion\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgers[0].budget").value(70))
                .andExpect(jsonPath("$.ledgers[1].budget").value(130));

        mockMvc.perform(post("/api/budget/transfers/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-t1\",\"transferKey\":\"h-key\","
                                + "\"details\":["
                                + "{\"sourceCampaignId\":\"A\",\"targetCampaignId\":\"B\","
                                + "\"amount\":30,\"sourceExpectedVersion\":1,\"targetExpectedVersion\":1},"
                                + "{\"sourceCampaignId\":\"B\",\"targetCampaignId\":\"A\","
                                + "\"amount\":5,\"sourceExpectedVersion\":1,\"targetExpectedVersion\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferKey").value("h-key"))
                .andExpect(jsonPath("$.details[0].sourceCampaignId").value("A"))
                .andExpect(jsonPath("$.ledgers[0].budget").value(75))
                .andExpect(jsonPath("$.ledgers[1].budget").value(125));

        mockMvc.perform(get("/api/budget/transfers/h-key/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.snapshots[0].campaignId").value("A"))
                .andExpect(jsonPath("$.snapshots[0].budgetBefore").value(100))
                .andExpect(jsonPath("$.snapshots[0].budgetAfter").value(75));

        // 明细只有 1 条，违反 2～50 条约束 → 400
        mockMvc.perform(post("/api/budget/transfers/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-bad\",\"transferKey\":\"h-bad-key\","
                                + "\"details\":["
                                + "{\"sourceCampaignId\":\"A\",\"targetCampaignId\":\"B\","
                                + "\"amount\":1,\"sourceExpectedVersion\":1,\"targetExpectedVersion\":1}]}"))
                .andExpect(status().isBadRequest());

        // 不存在转移证据 → 404
        mockMvc.perform(get("/api/budget/transfers/missing/evidence"))
                .andExpect(status().isNotFound());
    }
}
