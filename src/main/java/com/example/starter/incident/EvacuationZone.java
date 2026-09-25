package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 疏散区域实体，对应 evacuation_zones 表。
 * (incidentId, zoneKey) 唯一；(incidentId, fingerprint) 唯一，同指纹重放首次登记结果。
 * grids 为规范化（去空白、去重、字典序排序）后的网格集合；窗口为 UTC 左闭右开。
 * endedAt 仅 ENDED 有值。时间均为 UTC。
 */
public record EvacuationZone(
        long id,
        long incidentId,
        String zoneKey,
        int version,
        RiskLevel riskLevel,
        List<String> grids,
        Instant effectiveFrom,
        Instant effectiveTo,
        ZoneStatus status,
        String registeredBy,
        String fingerprint,
        Instant endedAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 以 UTC 左闭右开窗口判断区域在给定时刻是否有效：from ≤ at &lt; to，且区域未结束。
     */
    public boolean effectiveAt(Instant at) {
        return status == ZoneStatus.REGISTERED
                && !at.isBefore(effectiveFrom) && at.isBefore(effectiveTo);
    }

    /**
     * 窗口是否已到期（at ≥ to）。到期不等于已结束：结束还需完成阻断任务恢复裁决。
     */
    public boolean windowExpiredAt(Instant at) {
        return !at.isBefore(effectiveTo);
    }

    /**
     * 网格集合是否包含指定作业网格。
     */
    public boolean containsGrid(String grid) {
        return grids.contains(grid);
    }
}
