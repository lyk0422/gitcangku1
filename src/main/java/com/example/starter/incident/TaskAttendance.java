package com.example.starter.incident;

import java.time.Instant;

/**
 * 限时在场凭证实体，对应 task_attendance 表。
 * 每个 (taskId, guardian, generation) 唯一：同一监护人每次签到代次 generation 加 1，
 * 从 1 开始；有效期为 [issuedAt, expiresAt)，长度 60 秒，左闭右开，时刻均为 UTC。
 * status 见 {@link AttendanceStatus}；revokedAt 仅 REVOKED 有值，其余状态为空。
 */
public record TaskAttendance(
        long id,
        long taskId,
        String guardian,
        int generation,
        Instant issuedAt,
        Instant expiresAt,
        AttendanceStatus status,
        Instant revokedAt,
        Instant createdAt) {

    /**
     * 以给定 UTC 时刻判断凭证是否处于有效区间 [issuedAt, expiresAt)（左闭右开）。
     */
    public boolean validAt(Instant now) {
        return !now.isBefore(issuedAt) && now.isBefore(expiresAt);
    }
}
