package com.example.starter.incident;

import java.time.Duration;
import java.time.Instant;

/**
 * 遏制时限挂起区间实体，对应 incident_suspensions 表。
 * 一次挂起产生一条区间：suspendedAt 为挂起起始 UTC 时刻，恢复时以 resumedAt 封口；
 * 挂起原因 reason/挂起操作人 suspendedBy 落库后不再变更，恢复列仅允许从空写一次，
 * 因此区间历史不可改写。resumedAt 为空表示当前生效中的挂起（每事件至多一条）。
 */
public record Suspension(
        long id,
        long incidentId,
        String suspendKey,
        String reason,
        String suspendedBy,
        Instant suspendedAt,
        String resumeNote,
        String resumedBy,
        Instant resumedAt,
        Instant createdAt,
        Instant updatedAt) {

    /** 当前是否为未封口（生效中）挂起区间。 */
    public boolean isOpen() {
        return resumedAt == null;
    }

    /**
     * 截至指定时刻该区间计入的挂起时长（毫秒）：已封口按起止差值，
     * 生效中按给定时刻与起始时刻差值；时刻早于起始时为 0。
     */
    public long suspendedMillisUntil(Instant now) {
        Instant end = resumedAt != null ? resumedAt : now;
        if (end.isBefore(suspendedAt)) {
            return 0L;
        }
        return Duration.between(suspendedAt, end).toMillis();
    }
}
