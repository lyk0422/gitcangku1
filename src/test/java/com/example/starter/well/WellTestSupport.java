package com.example.starter.well;

import com.example.starter.MutableClock;
import com.example.starter.TestInfraConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试基类：真实 H2 内存库（MODE=MySQL），每个用例前清理场景数据并重置可控时钟。
 */
@SpringBootTest
@Import(TestInfraConfig.class)
public abstract class WellTestSupport {

    protected static final Instant T0 = Instant.parse("2026-10-04T00:00:00Z");

    @Autowired
    protected WellService wellService;

    @Autowired
    protected SwitchService switchService;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanScenario() {
        jdbc.update("DELETE FROM switch_request_sample");
        jdbc.update("DELETE FROM switch_request");
        jdbc.update("DELETE FROM pressure_sample");
        jdbc.update("DELETE FROM well_segment");
        clock.set(T0);
    }

    /** 建一个 2 小时前开始的注入段。 */
    protected WellSegment newInjectionSegment(String wellNo) {
        return wellService.createSegment(wellNo, WellSegment.MODE_INJECTION, T0.minus(Duration.ofHours(2)));
    }

    /** 在指定偏移（相对当前时刻的分钟数，负数表示过去）追加一个采样。 */
    protected PressureSample sampleAt(String wellNo, String requestId, String pressure,
                                      long minutesAgo) {
        return wellService.addSample(wellNo, requestId, new BigDecimal(pressure),
                T0.plus(Duration.ofMinutes(minutesAgo)));
    }

    /** 追加 4 个合格采样：-12/-9/-6/-3 分钟，读数均在 8 至 12 MPa 内。 */
    protected void seedFourValidSamples(String wellNo) {
        sampleAt(wellNo, wellNo + "-s1", "9.60", -12);
        sampleAt(wellNo, wellNo + "-s2", "10.20", -9);
        sampleAt(wellNo, wellNo + "-s3", "11.00", -6);
        sampleAt(wellNo, wellNo + "-s4", "10.40", -3);
    }
}