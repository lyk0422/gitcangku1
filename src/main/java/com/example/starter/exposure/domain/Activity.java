package com.example.starter.exposure.domain;

/**
 * 活动 PO。活动按 UTC 自然日配置总确认额度与 24 个小时预算，承载公告匀速投放。
 *
 * @param activityId   活动编号，全局唯一
 * @param version      预算配置乐观锁版本号，初始 0，每次配置变更 +1
 * @param createdAtUtc 创建时刻（epoch 毫秒，UTC）
 */
public record Activity(
        String activityId,
        int version,
        long createdAtUtc
) {
}
