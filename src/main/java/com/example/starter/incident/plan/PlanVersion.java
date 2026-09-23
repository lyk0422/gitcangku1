package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案版本实体，对应 plan_versions 表。
 * versionNo 方案内单调递增；baseVersionId 为草稿/合并版本的共同祖先（初始版本为空）；
 * expectedVersion 为草稿乐观锁序号，创建为 1，每次草稿变更加 1。
 */
public record PlanVersion(
        long id,
        long planId,
        int versionNo,
        PlanVersionStatus status,
        Long baseVersionId,
        int expectedVersion,
        String createdBy,
        Instant createdAt) {
}
