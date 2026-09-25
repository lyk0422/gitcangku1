package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件资源实体，对应 incident_resources 表。
 * resourceKey 全局唯一；holderIncidentId 为登记持有事件，借出期间不变更，
 * 借出期间的责任方由进行中的互助交接表达。
 * 时间均为 UTC。
 */
public record IncidentResource(
        long id,
        String resourceKey,
        long holderIncidentId,
        String acquiredBy,
        Instant acquiredAt) {
}
