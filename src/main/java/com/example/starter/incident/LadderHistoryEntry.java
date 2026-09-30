package com.example.starter.incident;

import java.time.Instant;

/**
 * 升级阶梯等级变更历史实体，对应 incident_ladder_history 表，只追加不回写。
 * riskScore/configVersion/actor/occurredAt 均为变更时快照；
 * 后续配置修订不回写历史记录。
 */
public record LadderHistoryEntry(
        long id,
        long incidentId,
        LadderChangeType changeType,
        int fromLevel,
        int toLevel,
        int riskScore,
        int configVersion,
        String actor,
        Instant occurredAt,
        Instant createdAt) {
}
