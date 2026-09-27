package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.service.RetirementPlanner.BlockPlan;
import com.example.starter.blind.service.RetirementPlanner.CodePlan;
import com.example.starter.blind.service.RetirementPlanner.RetirementPlan;
import com.example.starter.blind.service.RetirementPlanner.SeatState;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退役后态规划器纯单元测试：主流程配额重算、已全部分配、已退役、
 * 多代码整批后态失败（任一失败即拒绝，不留半成品语义）与区组名额边界。
 */
class RetirementPlannerTest {

    /** 构造标准实验席位：每区组 seat1/2=A，seat3/4=B；occupiedSeats/sealedSeats 标记现状。 */
    private static List<SeatState> seats(int blockCount, Set<String> occupiedKeys, Set<String> sealedKeys) {
        List<SeatState> result = new ArrayList<>();
        for (int block = 1; block <= blockCount; block++) {
            for (int seat = 1; seat <= 4; seat++) {
                String treatment = seat <= 2 ? "A" : "B";
                String key = block + "-" + seat;
                result.add(new SeatState(block, seat, treatment,
                        occupiedKeys.contains(key), sealedKeys.contains(key)));
            }
        }
        return result;
    }

    @Test
    void retireCodeB_noAllocations_sealsAllBSeats_andRecomputesBlockQuotas() {
        RetirementPlan plan = RetirementPlanner.plan(List.of("B"), seats(2, Set.of(), Set.of()), 2);

        CodePlan codeB = plan.codes().get(0);
        assertEquals("B", codeB.treatment());
        assertEquals(4, codeB.quotaBefore(), "要求值：B 设计总配额 4");
        assertEquals(0, codeB.assignedCount());
        assertEquals(0, codeB.sealedBefore());
        assertEquals(4, codeB.sealedNow(), "实际封存全部 4 个未分配 B 序号");
        assertEquals(0, codeB.quotaAfter(), "退役后 B 有效配额必须为 0");
        assertEquals(4, plan.sealedTotal());

        assertEquals(2, plan.blocks().size());
        for (BlockPlan block : plan.blocks()) {
            assertEquals(0, block.occupiedCount());
            assertEquals(4, block.availableBefore());
            assertEquals(2, block.sealedInBlock());
            assertEquals(2, block.availableAfter(), "每块剩余 2 个 A 名额，实际=要求至少 1");
            assertEquals(2, block.quotaAAfter());
            assertEquals(0, block.quotaBAfter());
        }
    }

    @Test
    void retireCodeA_withPartialAssignments_sealsOnlyUnassignedAndKeepsAssignedEffective() {
        // 块1 第1席 A 已分配（既有参与者保持有效，不封存）
        RetirementPlan plan = RetirementPlanner.plan(List.of("A"),
                seats(2, Set.of("1-1"), Set.of()), 2);

        CodePlan codeA = plan.codes().get(0);
        assertEquals(4, codeA.quotaBefore());
        assertEquals(1, codeA.assignedCount(), "已分配序号不封存、保持有效");
        assertEquals(3, codeA.sealedNow(), "实际封存剩余 3 个 A 序号，差额体现为 4-1=3");
        assertEquals(0, codeA.quotaAfter());

        BlockPlan block1 = plan.blocks().get(0);
        assertEquals(1, block1.occupiedCount());
        assertEquals(3, block1.availableBefore());
        assertEquals(1, block1.sealedInBlock());
        assertEquals(2, block1.availableAfter());
        assertEquals(0, block1.quotaAAfter());
        assertEquals(2, block1.quotaBAfter());

        BlockPlan block2 = plan.blocks().get(1);
        assertEquals(0, block2.occupiedCount());
        assertEquals(4, block2.availableBefore());
        assertEquals(2, block2.sealedInBlock());
        assertEquals(2, block2.availableAfter());
        assertEquals(0, block2.quotaAAfter());
        assertEquals(2, block2.quotaBAfter());
    }

    @Test
    void retireCodeFullyAllocated_is422WithDistinguishableCode() {
        // 4 个 A 序号全部分配
        ApiException ex = assertThrows(ApiException.class, () -> RetirementPlanner.plan(List.of("A"),
                seats(2, Set.of("1-1", "1-2", "2-1", "2-2"), Set.of()), 2));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("CODE_FULLY_ALLOCATED", ex.getCode());
    }

    @Test
    void retireAlreadyRetiredCode_is409WithDistinguishableCode() {
        // 4 个 A 序号此前已全部封存，无占用也无未分配
        ApiException ex = assertThrows(ApiException.class, () -> RetirementPlanner.plan(List.of("A"),
                seats(2, Set.of(), Set.of("1-1", "1-2", "2-1", "2-2")), 2));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("CODE_ALREADY_RETIRED", ex.getCode());
    }

    @Test
    void retireBothCodes_emptyExperiment_wholeBatchRejectedForInsufficientRemainingCode() {
        // 两个代码同时退役：每块 4 个未分配序号全封存，后态名额为 0，整批拒绝
        ApiException ex = assertThrows(ApiException.class, () -> RetirementPlanner.plan(List.of("A", "B"),
                seats(2, Set.of(), Set.of()), 2));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("INSUFFICIENT_REMAINING_CODE", ex.getCode());
    }

    @Test
    void insufficientRemaining_isGlobalCheck_otherBlocksVacancyKeepsRetirementValid() {
        // 块1：seat1(A)、seat3(B)、seat4(B) 占用，仅 seat2(A) 空；块2 全空。
        Set<String> occupied = Set.of("1-1", "1-3", "1-4");

        // 退役 A：封存 1-2、2-1、2-2 三个 A 序号；全局剩余 2-3/2-4 两个 B 名额 ≥1 → 通过
        RetirementPlan plan = RetirementPlanner.plan(List.of("A"), seats(2, occupied, Set.of()), 2);
        BlockPlan block1 = plan.blocks().get(0);
        assertEquals(1, block1.sealedInBlock());
        assertEquals(1, block1.availableBefore());
        assertEquals(0, block1.availableAfter(), "块1 自身名额归零，但全局仍有名额，允许");
        BlockPlan block2 = plan.blocks().get(1);
        assertEquals(2, block2.sealedInBlock());
        assertEquals(2, block2.availableAfter());
        assertEquals(3, plan.sealedTotal());

        // 块2 也全部占用后（实验 7/8 占用，唯一空位 1-2 是 A）：退役 A 会封存唯一全局空位 → 拒绝
        Set<String> occupiedMore = Set.of("1-1", "1-3", "1-4", "2-1", "2-2", "2-3", "2-4");
        ApiException ex = assertThrows(ApiException.class, () -> RetirementPlanner.plan(List.of("A"),
                seats(2, occupiedMore, Set.of()), 2));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("INSUFFICIENT_REMAINING_CODE", ex.getCode());
        assertTrue(ex.getMessage().contains("实际 0"));
        assertTrue(ex.getMessage().contains("差额 1"));
    }

    @Test
    void fullExperiment_isExemptFromRemainingQuotaRule() {
        // 实验满员（8 席全占用）：退役 A/B 直接 CODE_FULLY_ALLOCATED，不会落到名额校验
        Set<String> all = new java.util.HashSet<>();
        all.addAll(Set.of("1-1", "1-2", "1-3", "1-4", "2-1", "2-2", "2-3", "2-4"));
        ApiException ex = assertThrows(ApiException.class, () -> RetirementPlanner.plan(List.of("A", "B"),
                seats(2, all, Set.of()), 2));
        assertEquals("CODE_FULLY_ALLOCATED", ex.getCode());
    }

    @Test
    void previouslySealedSeats_areNotCountedAsAvailable_andNotSealedAgain() {
        // 块1：seat1(A)空、seat2(A)占用、seat3(B)空、seat4(B)此前已封存。
        Set<String> occupied = Set.of("1-2");
        Set<String> sealed = Set.of("1-4");
        // 退役 A：只封存 seat1；seat3(B) 保留使后态名额=1。
        RetirementPlan plan = RetirementPlanner.plan(List.of("A"), seats(1, occupied, sealed), 1);
        CodePlan codeA = plan.codes().get(0);
        assertEquals(2, codeA.quotaBefore());
        assertEquals(1, codeA.assignedCount());
        assertEquals(1, codeA.sealedNow(), "仅封存唯一未分配 A 序号，已封存/占用的不再封存");
        assertEquals(0, codeA.quotaAfter());
        BlockPlan block1 = plan.blocks().get(0);
        assertEquals(1, block1.occupiedCount());
        assertEquals(2, block1.availableBefore(), "可分配名额只含 seat1、seat3，已封存 seat4 不计入");
        assertEquals(1, block1.sealedInBlock());
        assertEquals(1, block1.availableAfter(), "seat3(B) 保留，实际 1 满足要求 1");
        assertEquals(0, block1.quotaAAfter());
        assertEquals(1, block1.quotaBAfter());
    }
}
