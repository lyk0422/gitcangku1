package com.example.starter.translation;

import com.example.starter.translation.domain.FreezeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结窗口 UTC 左闭右开区间判定的纯单元测试，不启动 Spring 与数据库。
 */
class FreezeStatusTest {

    private static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant END = Instant.parse("2026-10-01T11:00:00Z");

    @Test
    @DisplayName("窗口左闭：起始时刻 10:00:00 处于冻结中")
    void startIsInclusive() {
        assertThat(FreezeStatus.isActive(START, END, START)).isTrue();
        assertThat(FreezeStatus.isActive(START, END, Instant.parse("2026-10-01T10:00:00.001Z"))).isTrue();
    }

    @Test
    @DisplayName("窗口右开：结束时刻 11:00:00 已解冻")
    void endIsExclusive() {
        assertThat(FreezeStatus.isActive(START, END, END)).isFalse();
        assertThat(FreezeStatus.isActive(START, END, Instant.parse("2026-10-01T10:59:59.999Z"))).isTrue();
        assertThat(FreezeStatus.isActive(START, END, Instant.parse("2026-10-01T11:00:00.001Z"))).isFalse();
    }

    @Test
    @DisplayName("窗口外：开始之前不冻结")
    void beforeWindowIsNotActive() {
        assertThat(FreezeStatus.isActive(START, END, Instant.parse("2026-10-01T09:59:59Z"))).isFalse();
    }
}
