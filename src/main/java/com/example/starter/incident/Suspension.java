package com.example.starter.incident;

import java.time.Instant;

/**
 * 遏制时限挂起区间实体，对应 incident_suspensions 表。
 * 挂起时写入起始半区（suspendKey/reason/suspendedBy/suspendedAt 落库后不可改写）；
 * 恢复时同事务以恢复时刻封口（resumedBy/resumeNote/resumedAt 只写一次）。
 * resumedAt 为空表示挂起仍生效；同一事件同时至多一条生效记录（事件行锁保证）。
 * 时间均为 UTC 秒级以上的 Instant。
 */
public record Suspension(
        long id,
        long incidentId,
        String suspendKey,
        String reason,
        String suspendedBy,
        Instant suspendedAt,
        String resumedBy,
        String resumeNote,
        Instant resumedAt,
        Instant createdAt) {

    /**
     * 是否仍为生效中的挂起（尚未恢复封口）。
     */
    public boolean isActive() {
        return resumedAt == null;
    }
}
