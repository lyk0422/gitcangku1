package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件互助资源台账实体，对应 incident_resource_inventories 表（来源事件侧）。
 * 数量单位为件（非负整数）；totalQty 登记后不变，availableQty 随征调扣减、归还返还，
 * 恒满足 0 ≤ availableQty ≤ totalQty。时间均为 UTC。
 */
public record ResourceInventory(
        long id,
        long incidentId,
        String resourceCode,
        long totalQty,
        long availableQty,
        Instant createdAt,
        Instant updatedAt) {
}
