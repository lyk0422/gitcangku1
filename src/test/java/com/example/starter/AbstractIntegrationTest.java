package com.example.starter;

import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfiguration;

/**
 * 集成测试基类：每个测试类使用独立命名 H2 内存库（MODE=MySQL），
 * 每个用例前清理业务数据并重建合成站点，测试类结束后关闭内存库释放资源。
 */
@Import(TestClockConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractIntegrationTest {

    /** 合成窗口起点：2026-01-01T12:00:00Z。 */
    protected static final Instant WINDOW_START = Instant.parse("2026-01-01T12:00:00Z");
    /** 合成窗口终点：2026-01-01T12:10:00Z。 */
    protected static final Instant WINDOW_END = Instant.parse("2026-01-01T12:10:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetScenario() {
        jdbc.update("DELETE FROM slot_allocation");
        jdbc.update("DELETE FROM pass_prediction");
        jdbc.update("DELETE FROM prediction_series");
        jdbc.update("DELETE FROM ground_station");
        jdbc.update("INSERT INTO ground_station (code, antenna_band, min_elevation_deg,"
                + " capacity_mb_per_min) VALUES (?, ?, ?, ?)", "A", "S", 5.00, 100);
        jdbc.update("INSERT INTO ground_station (code, antenna_band, min_elevation_deg,"
                + " capacity_mb_per_min) VALUES (?, ?, ?, ?)", "B", "X", 10.00, 200);
        clock.setInstant(TestClockConfiguration.BASE_TIME);
    }

    @AfterAll
    void releaseDatabase() {
        jdbc.execute("SHUTDOWN");
    }
}