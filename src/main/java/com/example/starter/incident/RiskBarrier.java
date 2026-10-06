package com.example.starter.incident;

import java.time.Instant;

/**
 * 防护屏障实体，对应 incident_risk_barriers 表。
 * (incidentId, barrierKey) 唯一；削减率为 numerator/denominator（p/q，0≤p≤q，q≥1，均不超 1000）。
 * 仅绑定任务 DONE 且 revoked=false 时计入残余；每事件至多 5 个未撤销屏障。时间均为 UTC。
 */
public record RiskBarrier(
        long id,
        long incidentId,
        String barrierKey,
        String taskKey,
        int numerator,
        int denominator,
        boolean revoked,
        Instant createdAt,
        Instant updatedAt) {
}
