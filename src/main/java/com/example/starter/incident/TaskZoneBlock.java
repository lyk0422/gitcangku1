package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 任务疏散阻断快照实体，对应 incident_task_zone_blocks 表。
 * 区域生效时对未开始命中任务固化区域快照（键、版本、网格、窗口、等级）；
 * releasedAt 为空表示仍在阻断，区域结束、被修订取代或补发有效豁免时置位。
 */
public record TaskZoneBlock(
        long id,
        long incidentId,
        long taskId,
        long zoneId,
        String zoneKey,
        int zoneVersion,
        List<String> zoneGrids,
        Instant zoneEffectiveFrom,
        Instant zoneEffectiveTo,
        String riskLevel,
        Instant blockedAt,
        Instant releasedAt) {
}
