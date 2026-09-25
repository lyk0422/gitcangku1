package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 必需外部机构配置版本实体，对应 incident_agency_configs 表。
 * version 事件内从 1 递增（首次配置前当前版本视为 0）；
 * agencyCodes 为去重排序后的机构代码（0~5 个，空集合合法）；
 * 每事件至多一条 ACTIVE，替换后旧版本置为 REPLACED 且其回执仅归属旧版本。
 * 时间均为 UTC。
 */
public record AgencyConfig(
        long id,
        long incidentId,
        int version,
        List<String> agencyCodes,
        AgencyConfigStatus status,
        String createdBy,
        Instant createdAt) {
}
