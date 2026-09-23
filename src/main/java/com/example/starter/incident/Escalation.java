package com.example.starter.incident;

import java.time.Instant;

/**
 * 遏制逾期升级记录实体，对应 incident_escalations 表，每个事件至多一条。
 * deadlineAt 为首次接管 COMMANDING 时按等级确定的遏制期限（UTC），交接不重置；
 * triggeredAt 为检查入口发现逾期的触发 UTC 时刻；triggeredCommander 为触发当时的指挥人；
 * note/acknowledgedBy/acknowledgedAt 仅 ACKNOWLEDGED 状态有值，其余为空。
 * version 初始 1，确认/取消时递增，用于联合交接快照一致性比对。
 */
public record Escalation(
        long id,
        long incidentId,
        Instant deadlineAt,
        Instant triggeredAt,
        String triggeredCommander,
        EscalationStatus status,
        int version,
        String note,
        String acknowledgedBy,
        Instant acknowledgedAt,
        Instant createdAt,
        Instant updatedAt) {
}
