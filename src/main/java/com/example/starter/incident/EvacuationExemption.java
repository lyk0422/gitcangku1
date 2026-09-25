package com.example.starter.incident;

import java.time.Instant;

/**
 * 撤离豁免实体，对应 evacuation_exemptions 表。
 * 豁免绑定具体区域与该区域登记时的版本，仅对 (zoneId, exemptTaskKey) 生效；
 * 同一区域同一任务至多一条豁免（uk_exemption_scope）。时间均为 UTC。
 */
public record EvacuationExemption(
        long id,
        long incidentId,
        long zoneId,
        int version,
        String exemptTaskKey,
        String grantedBy,
        String commandKey,
        Instant createdAt) {
}
