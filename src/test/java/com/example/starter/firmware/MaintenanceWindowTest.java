package com.example.starter.firmware;

import com.example.starter.firmware.domain.MaintenanceWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维护窗口纯函数判定：跨零点窗口、左闭右开边界、UTC偏移与下一次窗口开始时刻。
 */
class MaintenanceWindowTest {

    @Test
    void 非跨零点窗口_左闭右开() {
        // 09:00~17:00
        assertThat(MaintenanceWindow.isInWindow(539, 540, 1020)).isFalse();
        assertThat(MaintenanceWindow.isInWindow(540, 540, 1020)).isTrue();
        assertThat(MaintenanceWindow.isInWindow(1019, 540, 1020)).isTrue();
        assertThat(MaintenanceWindow.isInWindow(1020, 540, 1020)).isFalse();
    }

    @Test
    void 跨零点窗口_起大于止() {
        // 22:00~06:00
        assertThat(MaintenanceWindow.isInWindow(1319, 1320, 360)).isFalse();
        assertThat(MaintenanceWindow.isInWindow(1320, 1320, 360)).isTrue();
        assertThat(MaintenanceWindow.isInWindow(1439, 1320, 360)).isTrue();
        assertThat(MaintenanceWindow.isInWindow(0, 1320, 360)).isTrue();
        assertThat(MaintenanceWindow.isInWindow(359, 1320, 360)).isTrue();
        assertThat(MaintenanceWindow.isInWindow(360, 1320, 360)).isFalse();
        assertThat(MaintenanceWindow.isInWindow(720, 1320, 360)).isFalse();
    }

    @Test
    void 本地分钟_正负偏移与跨日() {
        // UTC 10:00，东八区 → 本地 18:00
        assertThat(MaintenanceWindow.localMinute(Instant.parse("2026-09-24T10:00:00Z"), 480))
                .isEqualTo(1080);
        // UTC 01:00，西二区 → 前一日 23:00
        assertThat(MaintenanceWindow.localMinute(Instant.parse("2026-09-24T01:00:00Z"), -120))
                .isEqualTo(1380);
        // UTC 23:30，东一区 → 次日 00:30
        assertThat(MaintenanceWindow.localMinute(Instant.parse("2026-09-24T23:30:00Z"), 60))
                .isEqualTo(30);
    }

    @Test
    void 下一次窗口开始_当天与次日() {
        // 窗口 22:00 开始，UTC+8：UTC 10:00（本地18:00）→ 下一次 UTC 14:00
        assertThat(MaintenanceWindow.nextWindowStartUtc(
                Instant.parse("2026-09-24T10:00:00Z"), 480, 1320))
                .isEqualTo(Instant.parse("2026-09-24T14:00:00Z"));
        // 带秒时刻截断到分钟边界
        assertThat(MaintenanceWindow.nextWindowStartUtc(
                Instant.parse("2026-09-24T10:00:45Z"), 480, 1320))
                .isEqualTo(Instant.parse("2026-09-24T14:00:00Z"));
        // 窗口 09:00 开始，UTC+0：UTC 17:30 → 次日 09:00
        assertThat(MaintenanceWindow.nextWindowStartUtc(
                Instant.parse("2026-09-24T17:30:00Z"), 0, 540))
                .isEqualTo(Instant.parse("2026-09-25T09:00:00Z"));
        // 恰在窗口开始分钟（窗口内）→ 下一周期
        assertThat(MaintenanceWindow.nextWindowStartUtc(
                Instant.parse("2026-09-24T09:00:00Z"), 0, 540))
                .isEqualTo(Instant.parse("2026-09-25T09:00:00Z"));
    }

    @Test
    void 参数校验_偏移范围与起止不同() {
        assertThat(MaintenanceWindow.isValid(0, 540, 1020)).isTrue();
        assertThat(MaintenanceWindow.isValid(-720, 0, 1439)).isTrue();
        assertThat(MaintenanceWindow.isValid(840, 1320, 360)).isTrue();
        assertThat(MaintenanceWindow.isValid(-721, 540, 1020)).isFalse();
        assertThat(MaintenanceWindow.isValid(841, 540, 1020)).isFalse();
        assertThat(MaintenanceWindow.isValid(0, 540, 540)).isFalse();
        assertThat(MaintenanceWindow.isValid(0, -1, 1020)).isFalse();
        assertThat(MaintenanceWindow.isValid(0, 540, 1440)).isFalse();
    }
}
