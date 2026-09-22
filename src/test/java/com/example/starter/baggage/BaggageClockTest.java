package com.example.starter.baggage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

import com.example.starter.StarterApplication;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可控时钟测试：注入固定 UTC Clock，断言短卸登记时刻在响应、未补到清单及数据库列中
 * 均为固定 UTC 墙钟值，证明时间来源可替换且不依赖 JVM 默认时区换算。
 */
@SpringBootTest(classes = {StarterApplication.class, BaggageClockTest.FixedClockConfiguration.class})
@AutoConfigureMockMvc
class BaggageClockTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_trace_event");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void shortRegistration_usesControllableUtcClock() throws Exception {
        postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", "LEG1", "origin", "PEK", "destination", "SHA"))
                .andExpect(status().isCreated());
        postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", "LEG2", "origin", "SHA", "destination", "CAN"))
                .andExpect(status().isCreated());
        postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", "BAG2", "legIds", List.of("LEG1", "LEG2")))
                .andExpect(status().isCreated());
        postJson("/api/legs/LEG1/load", Map.of("requestId", UUID.randomUUID().toString(),
                "expectedVersion", 1, "bagTags", List.of("BAG2"))).andExpect(status().isOk());
        postJson("/api/legs/LEG1/seal", Map.of("requestId", UUID.randomUUID().toString(),
                "expectedVersion", 2)).andExpect(status().isOk());
        postJson("/api/legs/LEG1/arrive-discrepancy", Map.of("requestId", UUID.randomUUID().toString(),
                "expectedVersion", 3, "actualBagTags", List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredAt").value("2026-01-02T03:04:05Z"));

        String dbTime = jdbcTemplate.queryForObject(
                "SELECT short_registered_at FROM bag WHERE bag_tag = 'BAG2'", String.class);
        org.assertj.core.api.Assertions.assertThat(dbTime).startsWith("2026-01-02 03:04:05");
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
        }
    }
}
