package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务派发记录实体，对应 task_dispatches 表。
 * 每次派发（首次派发或 BLOCKED 后再派发）追加一行，按 seq 递增；
 * requestId 全局唯一：同键重放首次派发的任务与凭据版本，同键换资源/换任务返回 409。
 * region、credentialVersion 为派发当时校验通过后落下的凭据快照，历史不可变；
 * blockReason/blockedAt 仅 BLOCKED 有值；startedAt 仅 EXECUTING 有值。时间均为 UTC。
 */
public record TaskDispatch(
        long id,
        long taskId,
        int seq,
        String requestId,
        String resourceKey,
        String credentialVersion,
        String credentialType,
        String region,
        Instant expiresAt,
        DispatchStatus status,
        BlockReason blockReason,
        Instant blockedAt,
        Instant dispatchedAt,
        String dispatchedBy,
        Instant startedAt) {
}
