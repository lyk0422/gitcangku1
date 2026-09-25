package com.example.starter.incident;

import java.time.Instant;

/**
 * 外部机构回执实体，对应 incident_agency_receipts 表。
 * (incidentId, configVersion, agencyCode) 唯一：同一机构每个配置版本至多一条终态回执；
 * 回执归属提交时的配置版本，替换配置后旧回执仅归属旧版本，历史回执不可改写。
 * reason 仅 REJECT 必填非空；ackKey 为机构侧幂等键。时间均为 UTC。
 */
public record AgencyReceipt(
        long id,
        long incidentId,
        int configVersion,
        String agencyCode,
        AgencyReceiptType type,
        String reason,
        String ackKey,
        Instant createdAt) {
}
