package com.example.starter.blind.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机表摘要算法单元测试：确定性、绑定全部封存要素、不泄露序列、长度不合法时报错。
 */
class SequenceDigestTest {

    private static final List<String> SEQ_AABB = List.of("A", "A", "B", "B");

    @Test
    void compute_isDeterministic_and64LowerHex() {
        String d1 = SequenceDigest.compute("key-1", "EXP", 1, 1, 4, "A,B", SEQ_AABB);
        String d2 = SequenceDigest.compute("key-1", "EXP", 1, 1, 4, "A,B", SEQ_AABB);
        assertEquals(d1, d2);
        assertTrue(d1.matches("[0-9a-f]{64}"), "摘要应为 64 位小写十六进制: " + d1);
    }

    @Test
    void compute_bindsSealKeyExperimentBlockVersionCapacityCodesAndSequence() {
        String base = SequenceDigest.compute("key-1", "EXP", 1, 1, 4, "A,B", SEQ_AABB);
        assertNotEquals(base, SequenceDigest.compute("key-2", "EXP", 1, 1, 4, "A,B", SEQ_AABB),
                "sealKey 不同摘要必须不同");
        assertNotEquals(base, SequenceDigest.compute("key-1", "EXP2", 1, 1, 4, "A,B", SEQ_AABB),
                "实验不同摘要必须不同");
        assertNotEquals(base, SequenceDigest.compute("key-1", "EXP", 2, 1, 4, "A,B", SEQ_AABB),
                "区组不同摘要必须不同");
        assertNotEquals(base, SequenceDigest.compute("key-1", "EXP", 1, 2, 4, "A,B", SEQ_AABB),
                "版本不同摘要必须不同");
        assertNotEquals(base, SequenceDigest.compute("key-1", "EXP", 1, 1, 6, "A,B",
                List.of("A", "A", "A", "B", "B", "B")), "容量不同摘要必须不同");
        assertNotEquals(base, SequenceDigest.compute("key-1", "EXP", 1, 1, 4, "A,B",
                List.of("A", "B", "A", "B")), "序列顺序不同摘要必须不同（禁止重排）");
    }

    @Test
    void compute_digestDoesNotContainRawSequence() {
        String digest = SequenceDigest.compute("key-1", "EXP", 1, 1, 4, "A,B", SEQ_AABB);
        assertFalse(digest.contains("AABB"), "摘要不得泄露具体序列");
    }

    @Test
    void compute_rejectsSequenceLengthMismatch() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SequenceDigest.compute("k", "E", 1, 1, 4, "A,B", List.of("A", "A")));
        assertTrue(ex.getMessage().contains("序列长度必须与区组容量一致"));
    }
}
