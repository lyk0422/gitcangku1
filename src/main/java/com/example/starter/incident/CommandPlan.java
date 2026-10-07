package com.example.starter.incident;

import java.time.Instant;

/**
 * 指令计划实体，对应 command_plans 表。
 * version 为计划版本：创建为 1，每次执行/取消/补偿成功递增，补偿失败不变；
 * cancelledBy/cancelledAt 仅 COMPENSATING/CANCELLED 有值。时间均为 UTC。
 */
public record CommandPlan(
        long id,
        long incidentId,
        String planKey,
        PlanStatus status,
        int version,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        String cancelledBy,
        Instant cancelledAt) {
}
