package com.example.starter.incident;

import java.time.Instant;

/**
 * 封存包任务依赖边快照实体，对应 incident_seal_edges 表。
 * 固化封存时刻本事件任务 → 阻塞事件的依赖边（含边创建时刻），只增不改。时间均为 UTC。
 */
public record IncidentSealEdge(
        long id,
        long packageId,
        String taskKey,
        String blockerIncidentKey,
        Instant edgeCreatedAt) {
}
