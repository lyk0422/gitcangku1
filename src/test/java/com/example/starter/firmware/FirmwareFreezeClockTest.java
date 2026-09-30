package com.example.starter.firmware;

import com.example.starter.firmware.api.ApproveRestoreRequest;
import com.example.starter.firmware.api.FreezeStatusResponse;
import com.example.starter.firmware.api.FreezeVersionRequest;
import com.example.starter.firmware.api.RestoreFreezeRequest;
import com.example.starter.firmware.service.FreezeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结/恢复 UTC 时刻使用可控时钟验证（真实 H2 库落库后回读），不依赖系统时间与休眠。
 */
@SpringBootTest
class FirmwareFreezeClockTest {

    /**
     * 测试内可手动推进的固定时钟，替换默认的 systemUTC。
     */
    static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone = ZoneOffset.UTC;

            MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        @Primary
        Clock testBusinessClock() {
            return new MutableClock(Instant.parse("2026-09-30T07:00:00Z"));
        }
    }

    @Autowired
    private FreezeService freezeService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM version_freeze_restore");
        jdbc.update("DELETE FROM version_freeze");
        jdbc.update("DELETE FROM idempotency_record");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM version_freeze_restore");
        jdbc.update("DELETE FROM version_freeze");
    }

    @Test
    void 冻结申请审批时刻使用业务时钟_恢复后冻结时刻原因保留() {
        MutableClock mutableClock = (MutableClock) clock;
        Instant frozenAt = mutableClock.instant();

        var freeze = freezeService.freeze(
                new FreezeVersionRequest("r-fz", "2.0.0", "jia", "发现严重回退缺陷"));
        assertThat(freeze.frozenAtUtc()).isEqualTo("2026-09-30T07:00:00Z");
        assertThat(freeze.frozenAtUtc()).isEqualTo(frozenAt.toString());

        mutableClock.advance(Duration.ofMinutes(90));
        Instant requestedAt = mutableClock.instant();
        var restore = freezeService.requestRestore("2.0.0",
                new RestoreFreezeRequest("r-req", "jia", "回归已通过"));
        assertThat(restore.requestedAtUtc()).isEqualTo("2026-09-30T08:30:00Z");
        assertThat(restore.requestedAtUtc()).isEqualTo(requestedAt.toString());

        mutableClock.advance(Duration.ofMinutes(30));
        Instant approvedAt = mutableClock.instant();
        var approved = freezeService.approve("2.0.0", restore.restoreId(),
                new ApproveRestoreRequest("r-ap", "yi"));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.reviewedBy()).isEqualTo("yi");
        assertThat(approved.reviewedAtUtc()).isEqualTo("2026-09-30T09:00:00Z");
        assertThat(approved.reviewedAtUtc()).isEqualTo(approvedAt.toString());

        FreezeStatusResponse status = freezeService.status("2.0.0");
        assertThat(status.freeze().status()).isEqualTo("RESUMED");
        assertThat(status.freeze().approvedBy()).isEqualTo("yi");
        assertThat(status.freeze().restoredAtUtc()).isEqualTo("2026-09-30T09:00:00Z");
        // 恢复不抹除原创建人的原因与生效时刻
        assertThat(status.freeze().frozenBy()).isEqualTo("jia");
        assertThat(status.freeze().frozenReason()).isEqualTo("发现严重回退缺陷");
        assertThat(status.freeze().frozenAtUtc()).isEqualTo("2026-09-30T07:00:00Z");
        assertThat(status.restores()).hasSize(1);
        assertThat(status.restores().get(0).requestedAtUtc()).isEqualTo("2026-09-30T08:30:00Z");
    }
}
