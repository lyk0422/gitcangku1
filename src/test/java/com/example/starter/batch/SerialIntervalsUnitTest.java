package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 序号区间规范化与结构化指纹纯单元测试：相邻合并、重叠/越界 422、
 * 十进制同值同参、文本原样、字段无碰撞。
 */
class SerialIntervalsUnitTest {

    // ---------- 区间规范化 ----------

    @Test
    void normalize_mergesAdjacentIntervals() {
        List<SerialIntervals.Interval> merged = SerialIntervals.normalize(
                List.of(new int[]{1, 4}, new int[]{5, 10}));
        assertEquals(List.of(new SerialIntervals.Interval(1, 10)), merged);
        assertEquals(10, SerialIntervals.totalLength(merged));
    }

    @Test
    void normalize_sortsBeforeMerging() {
        List<SerialIntervals.Interval> merged = SerialIntervals.normalize(
                List.of(new int[]{20, 30}, new int[]{1, 4}, new int[]{5, 6}));
        assertEquals(List.of(new SerialIntervals.Interval(1, 6),
                new SerialIntervals.Interval(20, 30)), merged);
    }

    @Test
    void normalize_overlappingIntervalsRejected422() {
        ApiException ex = assertThrows(ApiException.class, () -> SerialIntervals.normalize(
                List.of(new int[]{1, 5}, new int[]{5, 8})));
        assertEquals(422, ex.status().value());
    }

    @Test
    void normalize_outOfRangeRejected422() {
        assertEquals(422, assertThrows(ApiException.class, () -> SerialIntervals.normalize(
                List.of(new int[]{0, 5}))).status().value());
        assertEquals(422, assertThrows(ApiException.class, () -> SerialIntervals.normalize(
                List.of(new int[]{1, 1_000_001}))).status().value());
        // 边界值合法
        assertEquals(List.of(new SerialIntervals.Interval(1, 1_000_000)),
                SerialIntervals.normalize(List.of(new int[]{1, 1_000_000})));
    }

    @Test
    void normalize_startAfterEndRejected422() {
        assertEquals(422, assertThrows(ApiException.class, () -> SerialIntervals.normalize(
                List.of(new int[]{5, 3}))).status().value());
    }

    @Test
    void containedInAny_checksSingleDomainInterval() {
        List<SerialIntervals.Interval> domain = List.of(
                new SerialIntervals.Interval(1, 4), new SerialIntervals.Interval(6, 10));
        assertTrue(SerialIntervals.containedInAny(new SerialIntervals.Interval(2, 3), domain));
        assertTrue(SerialIntervals.containedInAny(new SerialIntervals.Interval(6, 10), domain));
        // 跨两个域区间（覆盖空缺 5）不算包含
        assertFalse(SerialIntervals.containedInAny(new SerialIntervals.Interval(3, 7), domain));
        assertFalse(SerialIntervals.containedInAny(new SerialIntervals.Interval(5, 5), domain));
    }

    // ---------- 结构化指纹 ----------

    @Test
    void fingerprint_decimalSameValueSameParam() {
        String a = new CanonicalFingerprint().put("q", new BigDecimal("1.0")).sha256();
        String b = new CanonicalFingerprint().put("q", new BigDecimal("1.00")).sha256();
        assertEquals(a, b, "十进制同值（1.0 与 1.00）应得到同一指纹");
        String c = new CanonicalFingerprint().put("q", new BigDecimal("1.01")).sha256();
        assertNotEquals(a, c);
    }

    @Test
    void fingerprint_textComparedAsIs() {
        String a = new CanonicalFingerprint().put("q", "1.0").sha256();
        String b = new CanonicalFingerprint().put("q", "1.00").sha256();
        assertNotEquals(a, b, "文本原样比较，1.0 与 1.00 不同参");
    }

    @Test
    void fingerprint_fieldsDoNotCollide() {
        String a = new CanonicalFingerprint().put("ab", "c").sha256();
        String b = new CanonicalFingerprint().put("a", "bc").sha256();
        assertNotEquals(a, b, "长度前缀保证字段边界无碰撞");
        String c = new CanonicalFingerprint().put("k", "x=1:y").sha256();
        String d = new CanonicalFingerprint().put("k", "x").put("k2", "1:y").sha256();
        assertNotEquals(c, d);
    }

    @Test
    void fingerprint_fieldOrderSignificant() {
        String a = new CanonicalFingerprint().put("x", "1").put("y", "2").sha256();
        String b = new CanonicalFingerprint().put("y", "2").put("x", "1").sha256();
        assertNotEquals(a, b, "结构化比较按字段顺序，调用方需固定登记顺序");
    }
}
