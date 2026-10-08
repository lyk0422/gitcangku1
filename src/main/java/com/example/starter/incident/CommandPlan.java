package com.example.starter.incident;

import java.time.Instant;

/**
 * 指令计划实体，对应 command_plans 表。仅操作合成状态，无外部 HTTP 调用。
 * status 见 {@link PlanStatus}；version 为期望版本号，初始 0，每次写入加 1。
 * 时间均为 UTC。
 */
public record CommandPlan(
        long id,
        long incidentId,
        PlanStatus status,
        long version,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
