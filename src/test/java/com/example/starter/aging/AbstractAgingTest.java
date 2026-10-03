package com.example.starter.aging;

import com.example.starter.aging.service.AgingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 测试基类：真实 H2 内存库（MODE=MySQL），每个用例前清空业务表保证隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractAgingTest {

    /** 固定入库 UTC 时刻，日期轴为 2026-01-01 至 2026-01-07。 */
    protected static final Instant ENTRY = Instant.parse("2026-01-01T00:00:00Z");

    protected static final LocalDate DAY_ONE = LocalDate.of(2026, 1, 1);

    @Autowired
    protected AgingService service;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM release_snapshot_item");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM temperature_record");
        jdbc.update("DELETE FROM turn_record");
        jdbc.update("DELETE FROM aging_batch");
    }

    protected long newBatch(String code) {
        return service.createBatch(code, ENTRY).id();
    }

    /** 为日期轴第 dayIndex 天（0 起）补一条翻面与一条 10.0 摄氏度的有效温度。 */
    protected void fillDay(long batchId, int dayIndex) {
        LocalDate date = DAY_ONE.plusDays(dayIndex);
        service.confirmTurn(batchId, "turn-" + batchId + "-" + dayIndex, date, "op", null);
        service.addTemperature(batchId, date, new BigDecimal("10.0"), "qa");
    }

    protected void fillAllDays(long batchId) {
        for (int i = 0; i < AgingService.TARGET_DAYS; i++) {
            fillDay(batchId, i);
        }
    }

    protected int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}