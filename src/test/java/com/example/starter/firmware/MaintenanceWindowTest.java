package com.example.starter.firmware;

import com.example.starter.firmware.domain.MaintenanceWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维护窗口判定纯单元测试：左闭右开、跨零点、UTC 偏移与下一次窗口开始时刻。
 */
class MaintenanceWindowTest {

    private static Instant utc(String utc) {
        return Instant.parse(utc);
    }

    @Test
    void 普通窗口_左闭右开_起止边界() {
        // UTC 本地窗口 03:00~05:00（180~300 分钟）
        MaintenanceWindow window = new MaintenanceWindow(180, 300);

        assertThat(window.contains(utc("2026-09-24T02:59:00Z"), 0)).isFalse();
        assertThat(window.contains(utc("2026-09-24T03:00:00Z"), 0)).isTrue();
        assertThat(window.contains(utc("2026-09-24T04:59:00Z"), 0)).isTrue();
        // 结束分钟为右开边界
        assertThat(window.contains(utc("2026-09-24T05:00:00Z"), 0)).isFalse();
    }

    @Test
    void 跨零点窗口_起点后与零点前后判定() {
        // 本地 23:00~次日01:00（1380 起，60 止）
        MaintenanceWindow window = new MaintenanceWindow(1380, 60);

        assertThat(window.contains(utc("2026-09-24T22:30:00Z"), 0)).isFalse();
        assertThat(window.contains(utc("2026-09-24T23:00:00Z"), 0)).isTrue();
        assertThat(window.contains(utc("2026-09-24T23:59:00Z"), 0)).isTrue();
        assertThat(window.contains(utc("2026-09-25T00:00:00Z"), 0)).isTrue();
        assertThat(window.contains(utc("2026-09-25T00:30:00Z"), 0)).isTrue();
        // 次日 01:00 为右开边界
        assertThat(window.contains(utc("2026-09-25T01:00:00Z"), 0)).isFalse();
    }

    @Test
    void 偏移分钟_本地分钟等于UTC分钟加偏移() {
        // 窗口为本地 10:00~12:00（600~720）
        MaintenanceWindow window = new MaintenanceWindow(600, 720);

        // UTC 02:00 在东八区（+480）为本地 10:00，落在起点
        assertThat(window.contains(utc("2026-09-24T02:00:00Z"), 480)).isTrue();
        // UTC 04:00 在东八区为本地 12:00，右开边界外
        assertThat(window.contains(utc("2026-09-24T04:00:00Z"), 480)).isFalse();
        // 同一 UTC 02:00 在西五区（-300）为本地 21:00（前一日），窗口外
        assertThat(window.contains(utc("2026-09-24T02:00:00Z"), -300)).isFalse();
        // 极端偏移 +840（UTC+14）：UTC 10:00 -> 本地 24:00 = 00:00，窗口外
        assertThat(window.contains(utc("2026-09-24T10:00:00Z"), 840)).isFalse();
    }

    @Test
    void 跨零点加偏移_环绕正确() {
        // 本地 23:00~01:00，设备位于东八区
        MaintenanceWindow window = new MaintenanceWindow(1380, 60);

        // UTC 15:00 -> 本地 23:00，窗口起点
        assertThat(window.contains(utc("2026-09-24T15:00:00Z"), 480)).isTrue();
        // UTC 16:30 -> 本地 00:30，跨零点后窗口内
        assertThat(window.contains(utc("2026-09-24T16:30:00Z"), 480)).isTrue();
        // UTC 14:30 -> 本地 22:30，窗口外
        assertThat(window.contains(utc("2026-09-24T14:30:00Z"), 480)).isFalse();
        // UTC 17:00 -> 本地 01:00，右开边界外
        assertThat(window.contains(utc("2026-09-24T17:00:00Z"), 480)).isFalse();
    }

    @Test
    void 下一次窗口开始_普通窗口() {
        MaintenanceWindow window = new MaintenanceWindow(180, 300);

        // 窗口外 02:00 -> 当天 03:00
        assertThat(window.nextStart(utc("2026-09-24T02:00:00Z"), 0))
                .isEqualTo(utc("2026-09-24T03:00:00Z"));
        // 恰在起点：返回当前时刻
        assertThat(window.nextStart(utc("2026-09-24T03:00:00Z"), 0))
                .isEqualTo(utc("2026-09-24T03:00:00Z"));
        // 窗口结束后 05:00 -> 次日 03:00
        assertThat(window.nextStart(utc("2026-09-24T05:00:00Z"), 0))
                .isEqualTo(utc("2026-09-25T03:00:00Z"));
    }

    @Test
    void 下一次窗口开始_跨零点窗口() {
        MaintenanceWindow window = new MaintenanceWindow(1380, 60);

        // 22:30 窗口外 -> 当天 23:00
        assertThat(window.nextStart(utc("2026-09-24T22:30:00Z"), 0))
                .isEqualTo(utc("2026-09-24T23:00:00Z"));
        // 01:00 右开边界外 -> 当天 23:00
        assertThat(window.nextStart(utc("2026-09-24T01:00:00Z"), 0))
                .isEqualTo(utc("2026-09-24T23:00:00Z"));
    }

    @Test
    void 下一次窗口开始_考虑偏移() {
        // 本地 10:00 起，设备位于东八区：UTC 02:00 为窗口开始
        MaintenanceWindow window = new MaintenanceWindow(600, 720);

        // UTC 00:00 -> 本地 08:00，距本地 10:00 还差 120 分钟 -> UTC 02:00
        assertThat(window.nextStart(utc("2026-09-24T00:00:00Z"), 480))
                .isEqualTo(utc("2026-09-24T02:00:00Z"));
    }

    @Test
    void 全天窗口_始终在窗口内_下一次开始为当前时刻() {
        MaintenanceWindow allDay = new MaintenanceWindow(0, 0);

        assertThat(allDay.contains(utc("2026-09-24T02:00:00Z"), 480)).isTrue();
        assertThat(allDay.contains(utc("2026-09-24T23:59:00Z"), -300)).isTrue();
        assertThat(allDay.nextStart(utc("2026-09-24T02:00:00Z"), 0))
                .isEqualTo(utc("2026-09-24T02:00:00Z"));
    }
}
