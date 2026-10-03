package com.example.starter.exposure.domain;

/**
 * 公告活动 PO。活动是公告的分组，按 UTC 自然日配置总确认额度与 24 个小时预算，
 * 同活动下所有公告共享活动侧小时额度。
 *
 * @param activityId   活动编号，全局唯一
 * @param createdAtUtc 创建时刻，epoch 毫秒，UTC
 */
public record Activity(
        String activityId,
        long createdAtUtc
) {
}