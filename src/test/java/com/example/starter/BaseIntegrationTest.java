package com.example.starter;

import com.example.starter.service.DischargeService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 集成测试基类：真实 H2（MODE=MySQL），每个用例前清理全部业务表。 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class BaseIntegrationTest {

    /** 用例基准时刻（UTC）。 */
    protected static final Instant T0 = Instant.parse("2026-02-01T08:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected DischargeService dischargeService;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM handover_cell");
        jdbc.update("DELETE FROM handover");
        jdbc.update("DELETE FROM cell_isolation");
        jdbc.update("DELETE FROM discharge_reading");
        jdbc.update("DELETE FROM idempotency_key");
        jdbc.update("DELETE FROM battery_cell");
        jdbc.update("DELETE FROM discharge_batch");
        TestClockConfig.MUTABLE_CLOCK.setInstant(T0);
    }

    /** 登记一枚可用电芯。 */
    protected void givenCell(String cellCode) {
        jdbc.update(
                "INSERT INTO battery_cell (cell_code, status, batch_code, created_at)"
                        + " VALUES (?,?,NULL,?)",
                cellCode, "AVAILABLE", java.sql.Timestamp.from(T0));
    }

    /** 创建批次并返回批次编号。 */
    protected String givenBatch() {
        return dischargeService.createBatch().batchCode();
    }

    /** 合成请求编号。 */
    protected String requestId() {
        return "REQ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 统计表行数。 */
    protected long count(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}