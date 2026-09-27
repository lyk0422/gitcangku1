package com.example.starter.incident;

import java.time.Instant;

/**
 * 指挥日志封存包实体，对应 incident_seal_packages 表。
 * (incidentId, version) 唯一，版本事件内从 1 递增；覆盖区间为 [lowerBoundAt, cutoffAt)
 * 左闭右开；封存后不可变，后续任务修订、撤销或事件合并不回写。时间均为 UTC。
 */
public record IncidentSealPackage(
        long id,
        long incidentId,
        int version,
        Instant lowerBoundAt,
        Instant cutoffAt,
        int entryCount,
        int edgeCount,
        String sealedBy,
        Instant createdAt) {
}
