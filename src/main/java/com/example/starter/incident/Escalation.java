package com.example.starter.incident;

import java.time.Instant;

/**
 * “逾期未遏制”升级记录实体，对应 incident_escalations 表；每个事件至多一条。
 * deadline 为首次接管时按严重等级确定的遏制期限（UTC），交接不重置；
 * triggeredAt 为检查命中逾期的触发时刻（UTC），commander 为触发当时的当前指挥人；
 * dispositionNote/acknowledgedBy/acknowledgedAt 仅 ACKNOWLEDGED 有值，其余状态为空。
 */
public record Escalation(
        long id,
        long incidentId,
        EscalationStatus status,
        Instant deadline,
        Instant triggeredAt,
        String commander,
        String dispositionNote,
        String acknowledgedBy,
        Instant acknowledgedAt,
        Instant createdAt) {
}
