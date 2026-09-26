package com.example.starter.translation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间格式纯单元测试：统一 UTC、固定微秒精度，不依赖 Spring 上下文。
 */
class CitationTimeFormatTest {

    @Test
    @DisplayName("UTC 时间输出固定 6 位微秒、Z 结尾，纳秒部分截断为微秒")
    void formatUtcFixedMicros() {
        Instant instant = Instant.parse("2026-09-26T08:30:45.123456789Z");
        assertThat(CitationLockService.formatUtc(instant))
                .isEqualTo("2026-09-26T08:30:45.123456Z");
    }

    @Test
    @DisplayName("整秒时间补齐 6 位小数")
    void formatUtcPadsMicros() {
        Instant instant = Instant.parse("2026-01-02T03:04:05Z");
        assertThat(CitationLockService.formatUtc(instant))
                .isEqualTo("2026-01-02T03:04:05.000000Z");
    }
}
