package com.example.starter.exposure.domain;

/**
 * 活动 PO。活动按 UTC 自然日配置总确认额度，24 个小时预算单独存储于
 * activity_hour_budget 表且合计必须等于 {@link #dailyTotalCap}。
 *
 * @param activityId     活动编号，全局唯一
 * @param dailyTotalCap  每 UTC 日总确认额度，单位次，等于 24 个小时预算之和
 * @param version        配置版本号，从 1 开始，每次成功修改 +1
 * @param createdAtUtc   创建时刻（epoch 毫秒，UTC）
 */
public record Activity(
        String activityId,
        int dailyTotalCap,
        int version,
        long createdAtUtc
) {
}
