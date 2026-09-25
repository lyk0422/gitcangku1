package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 资源租约实体，对应 resource_leases 表。每任务至多一条当前租约
 * （currentFlag=1，唯一约束 uk_task_current_lease）；被替换后 currentFlag 置 NULL。
 * resourceVersion 为分配时资源资质版本快照，requiredCredentials 为分配时任务必需资质
 * 集合快照（字典序），二者与操作者、规范化任务集合、租约时段共同构成 leaseKey 指纹。
 * replacedBy/replacedAt 仅 REPLACED 有值。时间均为 UTC。
 */
public record ResourceLease(
        long id,
        long taskId,
        String resourceId,
        long resourceVersion,
        Instant leaseStart,
        Instant leaseEnd,
        List<String> requiredCredentials,
        LeaseStatus status,
        Long currentFlag,
        String createdBy,
        Instant createdAt,
        String replacedBy,
        Instant replacedAt) {
}
