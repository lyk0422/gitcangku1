package com.example.starter.incident;

import java.time.Instant;

/**
 * 撤离豁免实体，对应 zone_exemptions 表。
 * 按（事件，任务，区域，区域版本）唯一；仅当 zoneVersion 等于该区域谱系当前版本时有效，
 * 区域修订后旧版本豁免自动失效。允许任务创建前预授权。
 */
public record ZoneExemption(
        long id,
        long incidentId,
        String taskKey,
        long zoneId,
        String zoneKey,
        int zoneVersion,
        String reason,
        String grantedBy,
        Instant createdAt) {
}
