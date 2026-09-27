package com.example.starter.blind.dto;

/**
 * 处理代码诊断行（只读）；数量单位均为“个序号（席位）”。
 *
 * @param treatment     处理代码 A/B
 * @param quotaTotal    设计总配额（席位数），要求值
 * @param assignedCount 已分配数量（含退组），实际值
 * @param sealedCount   历次退役累计封存数量，实际值
 * @param availableQuota 剩余有效配额 = quotaTotal - assignedCount - sealedCount，实际值；
 *                       被退役代码为 0，新分配不得再使用该代码
 */
public record CodeQuotaView(
        String treatment,
        int quotaTotal,
        int assignedCount,
        int sealedCount,
        int availableQuota
) {
}
