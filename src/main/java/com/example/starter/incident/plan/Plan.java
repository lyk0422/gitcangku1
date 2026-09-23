package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 处置方案实体，对应 plans 表。
 * activeVersionId 为当前活动 planVersion id；写路径先锁定方案行，
 * 串行化合并发布、任务执行与草稿变更，保证并发按事务提交顺序生效。
 */
public record Plan(
        long id,
        String planKey,
        Long activeVersionId,
        Instant createdAt) {
}
