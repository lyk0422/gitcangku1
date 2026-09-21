package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置记录实体，对应 incident_actions 表。
 * occurredAt 为客户端上报的处置发生 UTC 时间；createdAt 为服务端落库 UTC 时间。
 * (incidentId, actionKey) 唯一，同键同内容幂等，同键不同内容冲突。
 */
public record IncidentAction(
        long id,
        long incidentId,
        String actionKey,
        String actionType,
        String note,
        Instant occurredAt,
        String actor,
        Instant createdAt) {

    /**
     * 判断两条记录的业务内容是否一致（用于 actionKey 幂等比对）。
     */
    public boolean sameContent(String actionType, String note, Instant occurredAt) {
        return this.actionType.equals(actionType)
                && this.note.equals(note)
                && this.occurredAt.equals(occurredAt);
    }
}
