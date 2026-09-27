package com.example.starter.blind.dto;

/**
 * 区组当前名额诊断行（只读，查询时刻实际值）。数量单位均为“个序号（席位）”。
 *
 * @param blockNo       区组号，从 1 开始
 * @param capacity      区组设计容量（要求值，固定 4）
 * @param occupiedCount 已占序号数（含退组），实际值
 * @param sealedCount   历次退役累计封存序号数，实际值
 * @param availableCount 当前可分配名额 = capacity - occupiedCount - sealedCount，实际值
 * @param quotaA        区组内代码 A 当前有效配额（未占用且未封存），实际值
 * @param quotaB        区组内代码 B 当前有效配额（未占用且未封存），实际值
 */
public record BlockAvailabilityView(
        int blockNo,
        int capacity,
        int occupiedCount,
        int sealedCount,
        int availableCount,
        int quotaA,
        int quotaB
) {
}
