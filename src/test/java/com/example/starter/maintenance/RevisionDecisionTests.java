package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.starter.maintenance.domain.RevisionDecision;

/**
 * 大幅修订阈值判定纯逻辑单元测试（不启动 Spring、不访问数据库）。
 */
class RevisionDecisionTests {

    @Test
    void requiresWitness_deltaWithinThreshold_effectiveDirectly() {
        // 题干场景：100 → 110 恰好等于阈值 10，属于阈值内，直接生效
        assertFalse(RevisionDecision.requiresWitness(100, 110, 10));
        // 小于阈值同样直接生效
        assertFalse(RevisionDecision.requiresWitness(100, 109, 10));
        assertFalse(RevisionDecision.requiresWitness(100, 100, 10));
    }

    @Test
    void requiresWitness_deltaExceedsThreshold_mustWitness() {
        // 题干场景：100 → 112，差额 12 > 阈值 10，须见证
        assertTrue(RevisionDecision.requiresWitness(100, 112, 10));
        // 大幅下修（倒退以外的修正）同样按差额绝对值判定
        assertTrue(RevisionDecision.requiresWitness(200, 180, 10));
    }

    @Test
    void delta_keepsSign() {
        assertEquals(12, RevisionDecision.delta(100, 112));
        assertEquals(-20, RevisionDecision.delta(200, 180));
        assertEquals(0, RevisionDecision.delta(100, 100));
    }
}
