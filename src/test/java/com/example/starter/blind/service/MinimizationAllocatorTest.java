package com.example.starter.blind.service;

import com.example.starter.blind.repo.AllocationRepository.BalanceRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 协变量最小化分配算法纯单元测试：
 * 四边际格 |A-B| 总和最小者胜出，同分选 A；满处理不参与；无席位返回 null。
 */
class MinimizationAllocatorTest {

    @Test
    void emptyBalance_tieBreaksToA() {
        // 空历史：模拟 A/B 分数相同（各为 2），同分选 A
        assertEquals("A", MinimizationAllocator.chooseTreatment(List.of(), 0, 0, 2, 2));
        assertEquals(2, MinimizationAllocator.imbalanceScore(List.of(), 0, 0, "A"));
        assertEquals(2, MinimizationAllocator.imbalanceScore(List.of(), 0, 0, "B"));
    }

    @Test
    void secondSameFeature_goesToB() {
        // 合成场景：第一 (0,0) 分 A 后，第二 (0,0) 模拟 A 得 4 分、模拟 B 得 0 分，选 B
        List<BalanceRow> rows = List.of(new BalanceRow("A", 0, 0));
        assertEquals(4, MinimizationAllocator.imbalanceScore(rows, 0, 0, "A"));
        assertEquals(0, MinimizationAllocator.imbalanceScore(rows, 0, 0, "B"));
        assertEquals("B", MinimizationAllocator.chooseTreatment(rows, 0, 0, 1, 1));
    }

    @Test
    void balancedHistory_tieBreaksToA() {
        // A(0,0)、B(0,0) 各一：第三 (0,0) 模拟两边同分，选 A
        List<BalanceRow> rows = List.of(new BalanceRow("A", 0, 0), new BalanceRow("B", 0, 0));
        assertEquals("A", MinimizationAllocator.chooseTreatment(rows, 0, 0, 1, 1));
    }

    @Test
    void fullTreatmentIsSkipped_andNoSeatReturnsNull() {
        // A 无剩余席：只能选 B
        assertEquals("B", MinimizationAllocator.chooseTreatment(List.of(), 0, 0, 0, 2));
        // B 无剩余席：只能选 A
        assertEquals("A", MinimizationAllocator.chooseTreatment(List.of(), 0, 0, 2, 0));
        // 两侧均无席位：满额
        assertNull(MinimizationAllocator.chooseTreatment(List.of(), 0, 0, 0, 0));
    }

    @Test
    void scoreSumsAllFourMarginalCells() {
        // 历史：A(0,0)、A(1,1)、B(0,1)；新参与者 (1,0)
        List<BalanceRow> rows = List.of(
                new BalanceRow("A", 0, 0),
                new BalanceRow("A", 1, 1),
                new BalanceRow("B", 0, 1));
        // 模拟 A：格 f1=0: |1-1|=0, f1=1: |2-0|=2, f2=0: |2-0|=2, f2=1: |1-1|=0 → 4
        assertEquals(4, MinimizationAllocator.imbalanceScore(rows, 1, 0, "A"));
        // 模拟 B：格 f1=0: |1-1|=0, f1=1: |1-1|=0, f2=0: |1-1|=0, f2=1: |1-1|=0 → 0
        assertEquals(0, MinimizationAllocator.imbalanceScore(rows, 1, 0, "B"));
        assertEquals("B", MinimizationAllocator.chooseTreatment(rows, 1, 0, 1, 1));
    }
}
