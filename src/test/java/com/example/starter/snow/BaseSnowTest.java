package com.example.starter.snow;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 测试基类：固定可控时钟、隔离 H2 内存库，每个用例前清空全部场景数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(BaseSnowTest.TestClockConfig.class)
abstract class BaseSnowTest {

    /** 固定当前时间：2026-01-10T12:30:00Z，窗口整点为 09/10/11 点。 */
    static final Instant FIXED_NOW = Instant.parse("2026-01-10T12:30:00Z");

    static final MutableClock CLOCK = new MutableClock(FIXED_NOW);

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper om;

    @Autowired
    protected SnowService service;

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @BeforeEach
    void cleanScenario() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM lift_decision");
        jdbc.update("DELETE FROM lift_confirmation");
        jdbc.update("DELETE FROM lift_review");
        jdbc.update("DELETE FROM reading_correction");
        jdbc.update("DELETE FROM closure_command");
        jdbc.update("DELETE FROM snow_reading");
        jdbc.update("DELETE FROM area");
        CLOCK.set(FIXED_NOW);
    }

    /** 窗口内第 index 个整点(0 最早)。 */
    protected Instant windowHour(int index) {
        return FIXED_NOW.truncatedTo(ChronoUnit.HOURS)
                .minus(SnowService.WINDOW_HOURS - index, ChronoUnit.HOURS);
    }

    protected void seedArea(String areaId) {
        jdbc.update("INSERT INTO area (id, name, created_at) VALUES (?, ?, ?)",
                areaId, areaId, java.sql.Timestamp.from(FIXED_NOW));
    }

    protected void seedClosure(String areaId, String reason) {
        jdbc.update("INSERT INTO closure_command (area_id, reason, status, created_at) VALUES (?, ?, 'OPEN', ?)",
                areaId, reason, java.sql.Timestamp.from(FIXED_NOW));
    }

    protected void seedReading(String areaId, Instant hour, int depthCm, int newSnowCm) {
        jdbc.update("INSERT INTO snow_reading (area_id, hour_utc, snow_depth_cm, new_snow_cm, source, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, 'SYNTHETIC', ?, ?)",
                areaId, java.sql.Timestamp.from(hour), depthCm, newSnowCm,
                "seed-" + areaId + "-" + hour, java.sql.Timestamp.from(FIXED_NOW));
    }

    /** 播种最近三个整点读数，新增积雪依次为给定值。 */
    protected void seedWindow(String areaId, int... newSnowValues) {
        for (int i = 0; i < newSnowValues.length; i++) {
            seedReading(areaId, windowHour(i), 100 + i, newSnowValues[i]);
        }
    }

    protected MvcResult post(String url, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
    }

    protected MvcResult get(String url) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url)).andReturn();
    }

    protected long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }
}