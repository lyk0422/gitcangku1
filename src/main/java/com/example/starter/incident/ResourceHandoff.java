package com.example.starter.incident;

import java.time.Instant;

/**
 * 跨事件互助资源交接实体，对应 resource_handoffs 表。
 * handoffKey 全局唯一；租约为 UTC 左闭右开区间 [leaseStart, leaseEnd)；
 * sourceVersion/targetVersion 为创建时双方事件乐观版本号；
 * endReason/endTriggeredAt 仅在目标关闭或租约到期触发结束后有值；
 * settledAt 仅 SETTLED 有值。时间均为 UTC。
 */
public record ResourceHandoff(
        long id,
        String handoffKey,
        long sourceIncidentId,
        long targetIncidentId,
        String receiver,
        long sourceVersion,
        long targetVersion,
        String operator,
        Instant leaseStart,
        Instant leaseEnd,
        HandoffStatus status,
        SettlementReason endReason,
        Instant endTriggeredAt,
        Instant createdAt,
        Instant settledAt) {
}
