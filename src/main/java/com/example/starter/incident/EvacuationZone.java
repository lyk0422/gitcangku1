package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 疏散区域实体，对应 incident_zones 表。
 * zoneKey 为指纹键（事件键+事件版本+规范化网格+窗口+等级+操作者+谱系版本号）；
 * groupKey 为谱系键（初始版本 zoneKey），修订产生新版本时不变；
 * supersededAt 仅非最新版本有值。窗口为 UTC 左闭右开 [effectiveFrom, effectiveTo)。
 */
public record EvacuationZone(
        long id,
        long incidentId,
        String zoneKey,
        String groupKey,
        int version,
        List<String> grids,
        Instant effectiveFrom,
        Instant effectiveTo,
        String riskLevel,
        String operator,
        Instant supersededAt,
        Instant createdAt) {

    /**
     * 判断当前时刻（UTC）是否处于生效窗口内（左闭右开）。
     */
    public boolean effectiveAt(Instant now) {
        return !now.isBefore(effectiveFrom) && now.isBefore(effectiveTo);
    }

    /**
     * 判断当前时刻是否已越过窗口终点（区域已结束）。
     */
    public boolean endedAt(Instant now) {
        return !now.isBefore(effectiveTo);
    }
}
