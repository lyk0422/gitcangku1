package com.example.starter.blind.dto;

/**
 * 退役代码配额快照视图：记录退役时刻的统计，写入后不可变。
 *
 * @param treatment      被退役的处理代码
 * @param allocatedCount 退役时已分配席位数（既有参与者分配保持有效）
 * @param sealedCount    退役时封存的未分配序号数
 * @param quotaBefore    退役前该代码总配额（每区组2席 × 当时区组数）
 * @param quotaAfter     退役后该代码剩余可分配配额，恒为 0
 */
public record RetiredCodeView(
        String treatment,
        int allocatedCount,
        int sealedCount,
        int quotaBefore,
        int quotaAfter
) {
}
