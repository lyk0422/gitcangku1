package com.example.starter.service;

import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.CreateInflorescenceRequest;
import com.example.starter.web.InflorescenceView;
import com.example.starter.web.PollenBatchView;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 服务层测试基类：真实 H2 内存库（MODE=MySQL），每个用例前清空场景数据并重置可控时钟。
 */
@SpringBootTest
@Import(TestClockConfig.class)
abstract class ServiceTestBase {

    protected static final Instant T0 = Instant.parse("2026-02-01T10:00:00Z");

    @Autowired
    protected PollinationService service;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM pollination_operation");
        jdbc.update("DELETE FROM inflorescence");
        jdbc.update("DELETE FROM pollen_batch");
        clock.set(T0);
    }

    /** 新建花序，firstOpenAt 为 ISO-8601 UTC 时刻。 */
    protected InflorescenceView newInflorescence(String code, String firstOpenAt) {
        return service.createInflorescence(new CreateInflorescenceRequest(code, Instant.parse(firstOpenAt)));
    }

    /** 登记 ACTIVE 花粉批次。 */
    protected PollenBatchView newBatch(String batchNo) {
        return service.createPollenBatch(batchNo);
    }

    /** 统计指定花序处于指定状态的操作数。 */
    protected long countOperations(long inflorescenceId, String status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pollination_operation WHERE inflorescence_id = ? AND status = ?",
                Long.class, inflorescenceId, status);
        return count == null ? 0 : count;
    }
}