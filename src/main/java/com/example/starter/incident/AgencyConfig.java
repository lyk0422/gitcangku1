package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 外部机构必需回执配置版本实体，对应 incident_agency_configs 表。
 * 每次修改配置新增一行，version 在事件内从 1 起单调递增；
 * agencyCodes 已去重并按字典序排序，空列表合法，表示无必需机构。
 * 历史版本行永久保留，用于追溯与旧版本回执归属。
 */
public record AgencyConfig(
        long id,
        long incidentId,
        int version,
        List<String> agencyCodes,
        String createdBy,
        Instant createdAt) {
}
