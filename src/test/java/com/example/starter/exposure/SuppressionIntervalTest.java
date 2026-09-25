package com.example.starter.exposure;

import com.example.starter.exposure.domain.SuppressionInterval;
import com.example.starter.exposure.domain.SuppressionIntervalStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抑制区间半开时间语义的纯单元测试：[start, end) 左闭右开。
 */
class SuppressionIntervalTest {

    private SuppressionInterval interval(long start, long end) {
        return new SuppressionInterval(
                "i1", "c1", "v1", start, end, end,
                SuppressionIntervalStatus.ACTIVE, start, start, null, null);
    }

    @Test
    @DisplayName("半开区间：start 命中、end 不命中、中间命中、区间外不命中")
    void halfOpenBoundaries() {
        long start = 1_000L;
        long end = 2_000L;
        SuppressionInterval interval = interval(start, end);

        assertTrue(interval.contains(start), "左边界 start 必须命中（左闭）");
        assertTrue(interval.contains(start + 1), "区间内时刻必须命中");
        assertTrue(interval.contains(end - 1), "end 前一毫秒必须命中");
        assertFalse(interval.contains(end), "右边界 end 不得命中（右开）");
        assertFalse(interval.contains(start - 1), "start 之前不得命中");
        assertFalse(interval.contains(end + 1), "end 之后不得命中");
    }
}
