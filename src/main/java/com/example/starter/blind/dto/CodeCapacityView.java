package com.example.starter.blind.dto;

/**
 * 处理代码配额诊断视图：按封存数量重算后的实时统计，只读。
 *
 * @param treatment            处理代码
 * @param status               ACTIVE=未退役；RETIRED=已退役
 * @param quotaTotal           该代码当前席位总数（退役代码为 已分配+已封存）
 * @param allocatedCount       已分配席位数
 * @param sealedCount          已封存席位数
 * @param remainingAllocatable 剩余可分配席位数（退役代码恒为 0）
 */
public record CodeCapacityView(
        String treatment,
        String status,
        int quotaTotal,
        int allocatedCount,
        int sealedCount,
        int remainingAllocatable
) {
}
