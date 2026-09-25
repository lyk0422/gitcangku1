package com.example.starter.blind.dto;

/**
 * 中心视图：含目标上限、剩余容量（目标上限减累计已分配数）、状态与当前协议版本；不含盲底。
 *
 * @param experimentId   实验编号
 * @param centerId       中心编号
 * @param targetCap      目标入组上限（人）
 * @param allocatedCount 累计已分配数（含退组，席位不释放）
 * @param remaining      剩余容量 = targetCap - allocatedCount，修订生效预留依据；负数表示已超发
 * @param status         ACTIVE / SUSPENDED
 * @param currentVersion 中心当前协议版本号
 * @param createdAt      激活时间，Unix 毫秒，UTC
 * @param suspendedAt    最近暂停时间，Unix 毫秒，UTC；null 表示当前未暂停
 * @param resumedAt      最近恢复时间，Unix 毫秒，UTC；null 表示从未恢复
 */
public record CenterView(
        String experimentId,
        String centerId,
        int targetCap,
        long allocatedCount,
        long remaining,
        String status,
        int currentVersion,
        long createdAt,
        Long suspendedAt,
        Long resumedAt
) {
}
