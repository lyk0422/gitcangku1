package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 资源租约实体，对应 resource_leases 表。
 * 批量分配时每个高危任务一条租约，共享同一 leaseKey（幂等键）；
 * (leaseKey, taskId) 唯一。leaseKey 指纹含操作者、资源版本、规范化任务集合、
 * 租约时段与资质集合，同键成功重放首次响应，失败不占键。
 * credentialCodes 为租约覆盖的资质集合快照（按代码排序）；时段为 UTC 半开区间
 * [leaseStart, leaseEnd)。replacedBy 仅 REPLACED 有值，指向新租约的 leaseKey。
 */
public record ResourceLease(
        long id,
        String leaseKey,
        long resourceId,
        int resourceVersion,
        long taskId,
        List<String> credentialCodes,
        Instant leaseStart,
        Instant leaseEnd,
        LeaseStatus status,
        String replacedBy,
        String operator,
        Instant createdAt,
        Instant updatedAt) {
}
