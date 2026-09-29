package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合格证纯规则单元测试：UTC 左闭右开区间边界、批号前缀匹配、同优先级重叠判定。
 */
class CertificateRulesTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-02-01T00:00:00Z");

    @Test
    void interval_isLeftClosedRightOpen() {
        // 起点含：恰在 validFrom 有效
        assertTrue(CertificateRules.isActiveAt(FROM, TO, FROM));
        // 区间内有效
        assertTrue(CertificateRules.isActiveAt(FROM, TO, Instant.parse("2026-01-15T12:00:00Z")));
        // 终点不含：恰在 validTo 已失效
        assertFalse(CertificateRules.isActiveAt(FROM, TO, TO));
        // 起点之前无效
        assertFalse(CertificateRules.isActiveAt(FROM, TO, FROM.minusSeconds(1)));
        // 终点之后无效
        assertFalse(CertificateRules.isActiveAt(FROM, TO, TO.plusSeconds(1)));
    }

    @Test
    void invalidInterval_rejected() {
        assertTrue(CertificateRules.isInvalidInterval(FROM, FROM), "零长度区间非法");
        assertTrue(CertificateRules.isInvalidInterval(TO, FROM), "终点早于起点非法");
        assertTrue(CertificateRules.isInvalidInterval(null, TO));
        assertTrue(CertificateRules.isInvalidInterval(FROM, null));
        assertFalse(CertificateRules.isInvalidInterval(FROM, TO));
    }

    @Test
    void prefixMatch_isStartsWith_andPicksLongestDeterministically() {
        assertEquals("LOT-A", CertificateRules.matchingPrefix("LOT-A-123",
                List.of("LOT", "LOT-A")));
        assertEquals("LOT", CertificateRules.matchingPrefix("LOT-B-123",
                List.of("LOT", "LOT-A")));
        assertNull(CertificateRules.matchingPrefix("X-LOT-1", List.of("LOT")));
        assertTrue(CertificateRules.matchesAnyPrefix("LOT-9", List.of("LOT")));
        // 等长多命中按字典序稳定
        assertEquals("ab", CertificateRules.matchingPrefix("abX", List.of("ac", "ab")));
    }

    @Test
    void duplicateAndOverlapDetection() {
        assertTrue(CertificateRules.hasDuplicate(List.of("a", "a")));
        assertFalse(CertificateRules.hasDuplicate(List.of("a", "b")));

        // 与同优先级既有 ACTIVE 声明前缀相等即重叠
        String[] overlap = CertificateRules.findSamePriorityOverlap(
                Set.of("LOT", "ABC"), List.of(Set.of("XYZ", "LOT")));
        assertNotNull(overlap);
        assertEquals("LOT", overlap[0]);
        // 同优先级前缀包含关系也重叠："LOT" 与 "LOT-A" 都会匹配 "LOT-A-1"，无法裁决
        String[] contained = CertificateRules.findSamePriorityOverlap(
                Set.of("LOT-A"), List.of(Set.of("LOT")));
        assertNotNull(contained);
        assertTrue(CertificateRules.overlap("LOT", "LOT-A"));
        // 不相干前缀不重叠；不同优先级集合不参与（调用方只传同优先级）
        assertNull(CertificateRules.findSamePriorityOverlap(
                Set.of("LOT-1"), List.of(Set.of("LOT-2"))));
    }

    @Test
    void fingerprint_isOrderAndLengthSensitive_andCollectionSafe() {
        String f1 = Fingerprints.sha256(List.of("op", "actor"),
                List.of(List.of("batch-1", "decl-1")));
        // 同参同指纹
        String f1Again = Fingerprints.sha256(List.of("op", "actor"),
                List.of(List.of("batch-1", "decl-1")));
        assertEquals(f1, f1Again);
        // 操作者不同指纹不同
        assertFalse(f1.equals(Fingerprints.sha256(List.of("op", "actor2"),
                List.of(List.of("batch-1", "decl-1")))));
        // 集合内容不同指纹不同
        assertFalse(f1.equals(Fingerprints.sha256(List.of("op", "actor"),
                List.of(List.of("batch-1", "decl-2")))));
        // 长度前缀编码：分隔符拼接会碰撞的输入不应碰撞
        // ["ab","c"] vs ["a","bc"] 若用未转义逗号/竖线拼接会得到相同字符串
        String splitA = Fingerprints.sha256(List.of("op"),
                List.of(List.of("ab", "c")));
        String splitB = Fingerprints.sha256(List.of("op"),
                List.of(List.of("a", "bc")));
        assertFalse(splitA.equals(splitB), "集合不得通过未转义分隔符拼接");
        // 集合元素个数参与摘要
        assertFalse(splitA.equals(Fingerprints.sha256(List.of("op"),
                List.of(List.of("ab", "c", "")))));
    }
}
