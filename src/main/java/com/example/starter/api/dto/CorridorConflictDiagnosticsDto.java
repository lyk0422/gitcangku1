package com.example.starter.api.dto;

import java.util.List;

/**
 * 走廊一致性诊断结果：当前仍有效的走廊与未让渡常规 ACTIVE 占用的时空冲突。
 * 不变量要求该结果恒为空。
 *
 * @param violationCount 实际冲突对数量（与 violations 长度一致）
 * @param violations     冲突对明细（按 corridorId、occupancyId 排序）
 */
public record CorridorConflictDiagnosticsDto(
        int violationCount,
        List<Violation> violations) {

    /**
     * 一对“同一时空既有走廊又有未让渡常规占用”的冲突。
     *
     * @param corridorId  有效走廊标识
     * @param occupancyId 未让渡常规 ACTIVE 占用标识
     */
    public record Violation(String corridorId, String occupancyId) {
    }
}
