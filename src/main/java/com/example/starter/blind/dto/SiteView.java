package com.example.starter.blind.dto;

/**
 * 中心视图；不包含激活密钥等敏感信息。
 *
 * @param experimentId           所属实验编号
 * @param siteCode               中心编号
 * @param status                 中心状态 INACTIVE/ACTIVE/SUSPENDED/CLOSED
 * @param targetEnrollmentLimit  目标入组上限（人）
 * @param generation             当前激活代次；未激活为 0
 * @param cumulativeAssignments  该中心累计分配数（含已退组，不退容量）
 * @param remainingCapacity      剩余可分配容量（上限减累计分配，不为负）
 * @param gateReason             当前新分配门禁原因；null 表示允许分配
 * @param createdAt              创建时间，Unix 毫秒，UTC
 * @param updatedAt              最近状态变更时间，Unix 毫秒，UTC
 */
public record SiteView(
        String experimentId,
        String siteCode,
        String status,
        int targetEnrollmentLimit,
        int generation,
        long cumulativeAssignments,
        long remainingCapacity,
        String gateReason,
        long createdAt,
        long updatedAt
) {
}
