package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件资源可用量实体，对应 incident_resource_inventories 表。
 * availableQuantity 为当前可征调数量（单位件），恒满足 &gt;= 0：
 * 征调提交时原子扣减，租约恰好还完关闭时一次性累计加回。
 * 时间均为 UTC。
 */
public record ResourceInventory(
        long id,
        long incidentId,
        String resourceCode,
        int availableQuantity,
        Instant createdAt,
        Instant updatedAt) {
}
