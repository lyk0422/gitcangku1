package com.example.starter.blind.dto;

/**
 * 中心视图：状态、目标入组上限与累计已分配数（含已退组），不含任何盲底。
 *
 * @param experimentId 实验编号
 * @param centerId     中心编号
 * @param targetCap    目标入组上限（人）
 * @param allocated    累计已分配数（含已退组，退组不释放容量）
 * @param remaining    剩余容量 = 目标上限 - 累计已分配数
 * @param status       ACTIVE / SUSPENDED
 * @param createdAt    创建（激活）时间，Unix 毫秒，UTC
 * @param updatedAt    最近状态变更时间，Unix 毫秒，UTC
 */
public record CenterView(
        String experimentId,
        String centerId,
        int targetCap,
        long allocated,
        long remaining,
        String status,
        long createdAt,
        long updatedAt
) {
}
