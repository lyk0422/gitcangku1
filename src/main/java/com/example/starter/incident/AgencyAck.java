package com.example.starter.incident;

import java.time.Instant;

/**
 * 外部机构终态回执实体，对应 incident_agency_acks 表。
 * (incidentId, configVersion, agencyCode) 唯一：同一机构每个配置版本至多一条终态回执。
 * 回执只归属提交时的配置版本；替换配置后旧行不参与新版本门禁且不可改写。
 * reason 仅 REJECT 有值（非空拒绝说明），CONFIRM 为 null。时间均为 UTC。
 */
public record AgencyAck(
        long id,
        long incidentId,
        int configVersion,
        String agencyCode,
        AgencyAckType ackType,
        String reason,
        String submittedBy,
        Instant submittedAt) {
}
