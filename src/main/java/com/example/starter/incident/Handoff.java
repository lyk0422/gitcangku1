package com.example.starter.incident;

import java.time.Instant;

/**
 * 跨事件互助资源交接实体，对应 resource_handoffs 表。
 * 记录来源/目标事件及其交接时版本、UTC 左闭右开租约 [leaseStart, leaseEnd)、操作者；
 * handoffKey 为调用方幂等键，其指纹含两事件版本、资源、时段与操作者。
 * ACTIVE 期间资源责任在目标事件；SETTLED 为终态，结算明细见 HandoffSettlement。
 * 时间均为 UTC。
 */
public record Handoff(
        long id,
        String handoffKey,
        long resourceId,
        long sourceIncidentId,
        long targetIncidentId,
        long sourceVersion,
        long targetVersion,
        Instant leaseStart,
        Instant leaseEnd,
        String operator,
        String receiver,
        HandoffStatus status,
        Instant settledAt,
        Instant createdAt) {
}
