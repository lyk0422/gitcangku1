package com.example.starter.blind.dto;

/**
 * 退役批次中单个处理代码的不可变配额快照。
 * 数量单位均为“个序号（席位）”。
 *
 * @param treatment     被退役的处理代码 A/B
 * @param quotaBefore   退役前该代码总配额（席位数），要求值（设计值）
 * @param assignedCount 提交时刻已分配数量（含退组，席位不释放），实际值
 * @param sealedCount   本次封存的未分配序号数量，实际值（必然大于 0）
 * @param quotaAfter    退役后有效配额 = quotaBefore - assignedCount - 历次封存 - sealedCount，
 *                      被退役代码该值为 0
 */
public record RetiredCodeView(
        String treatment,
        int quotaBefore,
        int assignedCount,
        int sealedCount,
        int quotaAfter
) {
}
