package com.example.starter.blind.dto;

import java.util.List;

/**
 * 试验中心视图：代次、双人激活记录、累计分配数与状态门禁原因。
 *
 * @param experimentId   所属实验编号
 * @param siteCode       中心编号
 * @param status         PENDING / ACTIVE / SUSPENDED / CLOSED
 * @param generation     激活代次，0=从未激活
 * @param targetCap      目标入组上限（人）
 * @param allocatedCount 中心累计分配数（含已退组，容量不回收）
 * @param gateReason     当前拒绝新分配的门禁原因；允许分配时为 null。
 *                       取值：EXPERIMENT_CLOSED / SITE_NOT_ACTIVE / SITE_SUSPENDED /
 *                       SITE_CLOSED / SITE_CAP_REACHED
 * @param createdAt      创建时间，Unix 毫秒，UTC
 * @param closedAt       关闭时间，Unix 毫秒，UTC；未关闭为 null
 * @param activations    双人激活记录（不可变），按代次升序
 */
public record SiteView(
        String experimentId,
        String siteCode,
        String status,
        int generation,
        int targetCap,
        long allocatedCount,
        String gateReason,
        long createdAt,
        Long closedAt,
        List<SiteActivationView> activations
) {
}
