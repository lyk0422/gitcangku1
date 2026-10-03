package com.example.starter.calibration;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 时间口径测试：使用可控时钟验证离群检测、复核与放行时间均以 UTC 统一写入。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchClockTest {

    private static final Instant FIXED = Instant.parse("2026-06-01T08:30:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM batch_release_snapshot");
        jdbc.update("DELETE FROM batch_stats_record");
        jdbc.update("DELETE FROM adjudication_record");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_reading");
        jdbc.update("DELETE FROM measurement_batch");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        Mockito.when(clock.instant()).thenReturn(FIXED);
    }

    @Test
    void outlierAndReleaseTimestampsComeFromUnifiedUtcClock() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-T1","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-T1","instrumentId":"INS-T1","targetValue":"10",
                                 "tolerance":"0.5"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-06-01T08:30:00Z"));

        String body = mvc.perform(post("/api/batches/{key}/readings", "B-T1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readingKey":"RD-T1","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"10.9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-06-01T08:30:00Z"))
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId"))
                .longValue();

        String review = """
                {"reviewer":"%s","decision":"ACCEPT","reason":"确认",
                 "batchRevision":1,"certificateVersion":1}""";
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON).content(review.formatted("bob")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-06-01T08:30:00Z"));
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON).content(review.formatted("dave")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/batches/{key}/release", "B-T1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAt").value("2026-06-01T08:30:00Z"));

        // 数据库中的不可变记录时间同为受控 UTC 墙钟时刻（DATETIME(6) 存 UTC）
        java.time.LocalDateTime expected = java.time.LocalDateTime.ofInstant(
                FIXED, java.time.ZoneOffset.UTC);
        org.junit.jupiter.api.Assertions.assertEquals(expected, jdbc.queryForObject(
                "SELECT detected_at FROM outlier_record", java.time.LocalDateTime.class));
        org.junit.jupiter.api.Assertions.assertEquals(expected, jdbc.queryForObject(
                "SELECT decided_at FROM adjudication_record", java.time.LocalDateTime.class));
        org.junit.jupiter.api.Assertions.assertEquals(expected, jdbc.queryForObject(
                "SELECT released_at FROM batch_release_snapshot", java.time.LocalDateTime.class));
    }
}