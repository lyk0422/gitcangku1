package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.domain.MeterNormalization;

/**
 * 量程回绕归一化纯单元测试：归一公式、非法输入与 long 溢出边界。
 */
class MeterNormalizationTest {

    @Test
    void normalize_basicAndWrap() {
        assertEquals(990, MeterNormalization.normalize(1000, 990, 0));
        // 合成场景：raw 从 990 回到 5，显式 wrapCount=1 → 归一 1005，而非 5
        assertEquals(1005, MeterNormalization.normalize(1000, 5, 1));
        assertEquals(0, MeterNormalization.normalize(1000, 0, 0));
        assertEquals(999, MeterNormalization.normalize(1000, 999, 0));
        assertEquals(2000, MeterNormalization.normalize(1000, 0, 2));
    }

    @Test
    void normalize_rawOutOfRange_rejected400() {
        ApiException negative = assertThrows(ApiException.class,
                () -> MeterNormalization.normalize(1000, -1, 0));
        assertEquals(HttpStatus.BAD_REQUEST, negative.status());
        assertEquals("RAW_OUT_OF_RANGE", negative.code());

        // raw 必须严格小于 M
        ApiException atRange = assertThrows(ApiException.class,
                () -> MeterNormalization.normalize(1000, 1000, 0));
        assertEquals(HttpStatus.BAD_REQUEST, atRange.status());
        assertEquals("RAW_OUT_OF_RANGE", atRange.code());
    }

    @Test
    void normalize_negativeWrapCount_rejected400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> MeterNormalization.normalize(1000, 5, -1));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("WRAP_COUNT_NEGATIVE", ex.code());
    }

    @Test
    void normalize_overflow_rejected400() {
        // 乘法溢出
        ApiException multiply = assertThrows(ApiException.class,
                () -> MeterNormalization.normalize(1000, 0, Long.MAX_VALUE));
        assertEquals(HttpStatus.BAD_REQUEST, multiply.status());
        assertEquals("CUMULATIVE_OVERFLOW", multiply.code());

        // 加法溢出：乘积不溢出但加 raw 后溢出
        ApiException add = assertThrows(ApiException.class,
                () -> MeterNormalization.normalize(Long.MAX_VALUE, Long.MAX_VALUE - 1, 1));
        assertEquals(HttpStatus.BAD_REQUEST, add.status());
        assertEquals("CUMULATIVE_OVERFLOW", add.code());
    }

    @Test
    void normalize_maxInRangeValue_noOverflow() {
        // 恰好不溢出的最大值边界：wrapCount=1, M=Long.MAX_VALUE/2+1 范围内取值
        long m = Long.MAX_VALUE / 2;
        assertEquals(m + (m - 1), MeterNormalization.normalize(m, m - 1, 1));
    }
}
