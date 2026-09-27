package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 维护窗口折叠、左闭右开重叠判定与时刻匹配的纯逻辑单元测试（不启动 Spring、不依赖数据库）。
 */
class EffectiveMaintenanceWindowsTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-01T02:00:00Z");
    private static final Instant T3 = Instant.parse("2026-09-01T03:00:00Z");
    private static final Instant T4 = Instant.parse("2026-09-01T04:00:00Z");

    private MaintenanceWindow register(String id, int version, Instant start, Instant end) {
        return new MaintenanceWindow(id, "st-1", version, MaintenanceWindowKind.REGISTER,
                start, end, null, false, T0.plusSeconds(version));
    }

    private MaintenanceWindow correction(String id, int version, String supersedes,
                                         Instant start, Instant end, boolean retract) {
        return new MaintenanceWindow(id, "st-1", version, MaintenanceWindowKind.CORRECTION,
                start, end, supersedes, retract, T0.plusSeconds(version));
    }

    @Test
    void foldKeepsRegisteredWindows() {
        List<MaintenanceWindow> effective = EffectiveMaintenanceWindows.fold(List.of(
                register("w1", 1, T1, T2),
                register("w2", 2, T3, T4)));
        assertThat(effective).extracting(MaintenanceWindow::windowId)
                .containsExactly("w1", "w2");
    }

    @Test
    void foldReplacesTargetedWindowWithCorrection() {
        // w2 更正替换 w1 的区间；w1 不再有效，w2 生效
        List<MaintenanceWindow> effective = EffectiveMaintenanceWindows.fold(List.of(
                register("w1", 1, T1, T2),
                correction("w2", 2, "w1", T2, T3, false)));
        assertThat(effective).extracting(MaintenanceWindow::windowId).containsExactly("w2");
        assertThat(effective.get(0).startAtUtc()).isEqualTo(T2);
    }

    @Test
    void foldRetractionRemovesWindowWithoutAddingRange() {
        List<MaintenanceWindow> effective = EffectiveMaintenanceWindows.fold(List.of(
                register("w1", 1, T1, T2),
                correction("w1r", 2, "w1", null, null, true)));
        assertThat(effective).isEmpty();
    }

    @Test
    void foldRejectsCorrectingHistoricalOrUnknownWindow() {
        // w2 已替换 w1，再对历史窗口 w1 追加更正必须拒绝：历史窗口不可修改，只能追加更正当前有效窗口
        assertThatThrownBy(() -> EffectiveMaintenanceWindows.fold(List.of(
                register("w1", 1, T1, T2),
                correction("w2", 2, "w1", T2, T3, false),
                correction("w3", 3, "w1", T3, T4, false))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not effective");
        // 未知目标同样拒绝
        assertThatThrownBy(() -> EffectiveMaintenanceWindows.fold(List.of(
                correction("wx", 1, "missing", T1, T2, false))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void overlapIsHalfOpenAndEndpointTouchingDoesNotOverlap() {
        // [1,2) 与 [2,3) 端点相接：不重叠
        assertThat(EffectiveMaintenanceWindows.overlaps(T1, T2, T2, T3)).isFalse();
        // 部分重叠
        assertThat(EffectiveMaintenanceWindows.overlaps(T1, T3, T2, T4)).isTrue();
        // 包含
        assertThat(EffectiveMaintenanceWindows.overlaps(T1, T4, T2, T3)).isTrue();
        // 完全分离
        assertThat(EffectiveMaintenanceWindows.overlaps(T1, T2, T3, T4)).isFalse();
    }

    @Test
    void matchUsesHalfOpenIntervalStartInclusiveEndExclusive() {
        MaintenanceWindow window = register("w1", 1, T1, T3);
        // 左闭：开始时刻命中
        assertThat(EffectiveMaintenanceWindows.match(List.of(window), T1)).isEqualTo(window);
        // 区间内命中
        assertThat(EffectiveMaintenanceWindows.match(List.of(window), T2)).isEqualTo(window);
        // 右开：结束时刻不命中
        assertThat(EffectiveMaintenanceWindows.match(List.of(window), T3)).isNull();
        // 区间外不命中
        assertThat(EffectiveMaintenanceWindows.match(List.of(window), T4)).isNull();
    }

    @Test
    void matchPicksHighestMaintenanceVersionWhenMultipleHits() {
        MaintenanceWindow older = register("w1", 1, T1, T4);
        MaintenanceWindow newer = correction("w2", 2, "w1", T1, T4, false);
        // 正常折叠后不会重叠；直接验证多命中时取维护版本最高者，保证结果确定
        assertThat(EffectiveMaintenanceWindows.match(List.of(older, newer), T2))
                .extracting(MaintenanceWindow::windowId).isEqualTo("w2");
    }

    @Test
    void durationSecondsReflectsActualRangeLength() {
        assertThat(register("w1", 1, T1, T4).durationSeconds()).isEqualTo(10800L);
        MaintenanceWindow retraction = correction("w1r", 2, "w1", null, null, true);
        assertThat(retraction.durationSeconds()).isNull();
    }
}
