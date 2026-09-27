package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.starter.maintenance.api.dto.LockPointInput;
import com.example.starter.maintenance.service.FingerprintUtil;

/**
 * 幂等指纹构造的纯单元测试：结构化规范化 JSON、字段字典序、集合顺序无关、
 * null 语义稳定，且不依赖未转义分隔符拼接。
 */
class FingerprintUtilTest {

    @Test
    void canonical_fieldOrderIsNormalized() {
        Map<String, Object> ab = new HashMap<>();
        ab.put("a", 1);
        ab.put("b", "x");
        Map<String, Object> ba = new HashMap<>();
        ba.put("b", "x");
        ba.put("a", 1);

        assertEquals(FingerprintUtil.canonical(ab), FingerprintUtil.canonical(ba));
        assertEquals("{\"a\":1,\"b\":\"x\"}", FingerprintUtil.canonical(ab));
    }

    @Test
    void canonical_collectionOrderIsIrrelevant() {
        String forward = FingerprintUtil.canonical(List.of("LP-2", "LP-1", "LP-3"));
        String backward = FingerprintUtil.canonical(List.of("LP-3", "LP-1", "LP-2"));
        assertEquals(backward, forward);
        assertEquals("[\"LP-1\",\"LP-2\",\"LP-3\"]", forward);
    }

    @Test
    void canonical_lockPointSetOrderIrrelevantButContentDifferencesMatter() {
        List<LockPointInput> one = List.of(
                new LockPointInput("LP-1", "总闸"),
                new LockPointInput("LP-2", null));
        List<LockPointInput> reordered = List.of(
                new LockPointInput("LP-2", null),
                new LockPointInput("LP-1", "总闸"));
        assertEquals(FingerprintUtil.canonical(one), FingerprintUtil.canonical(reordered));

        List<LockPointInput> differentLabel = List.of(
                new LockPointInput("LP-1", "分闸"),
                new LockPointInput("LP-2", null));
        assertNotEquals(FingerprintUtil.canonical(one), FingerprintUtil.canonical(differentLabel));
    }

    @Test
    void canonical_nullIsPreservedAndDistinctFromEmptyString() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("operatorId", null);
        Map<String, Object> withEmpty = new HashMap<>();
        withEmpty.put("operatorId", "");

        assertEquals("{\"operatorId\":null}", FingerprintUtil.canonical(withNull));
        assertNotEquals(FingerprintUtil.canonical(withNull), FingerprintUtil.canonical(withEmpty));
    }

    @Test
    void canonical_doesNotUseRawDelimiterJoin() {
        // 若用未转义 "|" 拼接，下列两组参数会碰撞；结构化 JSON 必须能区分。
        Map<String, Object> first = new HashMap<>();
        first.put("x", "a|b");
        first.put("y", "c");
        Map<String, Object> second = new HashMap<>();
        second.put("x", "a");
        second.put("y", "b|c");
        assertNotEquals(FingerprintUtil.canonical(first), FingerprintUtil.canonical(second));
    }

    @Test
    void canonical_instantUsesUtcIsoText() {
        Map<String, Object> params = new HashMap<>();
        params.put("sampledAt", Instant.parse("2026-01-01T10:00:00Z"));
        assertEquals("{\"sampledAt\":\"2026-01-01T10:00:00Z\"}", FingerprintUtil.canonical(params));
    }

    @Test
    void canonical_nestedStructuresAreFullyNormalized() {
        Map<String, Object> outer1 = new HashMap<>();
        outer1.put("operator", "alice");
        outer1.put("points", List.of(
                new LockPointInput("b", null), new LockPointInput("a", "标签")));
        Map<String, Object> outer2 = new HashMap<>();
        outer2.put("points", List.of(
                new LockPointInput("a", "标签"), new LockPointInput("b", null)));
        outer2.put("operator", "alice");
        assertEquals(FingerprintUtil.canonical(outer1), FingerprintUtil.canonical(outer2));
    }
}
