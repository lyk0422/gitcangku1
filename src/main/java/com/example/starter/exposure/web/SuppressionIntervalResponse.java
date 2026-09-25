package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.SuppressionInterval;
import com.example.starter.exposure.domain.SuppressionIntervalStatus;

/**
 * 访客抑制区间视图。
 *
 * @param intervalId        区间编号
 * @param campaignId        所属公告编号
 * @param visitorId         被抑制访客编号
 * @param startAtUtc        生效开始时刻，epoch 毫秒，UTC，左闭
 * @param endAtUtc          当前生效结束时刻，epoch 毫秒，UTC，右开；提前结束时缩短
 * @param originalEndAtUtc  创建时计划结束时刻，epoch 毫秒，UTC，不可变
 * @param status            区间状态：ACTIVE/DELETED
 * @param createdAtUtc      创建时刻，epoch 毫秒，UTC
 * @param updatedAtUtc      最近变更时刻，epoch 毫秒，UTC
 * @param deletedAtUtc      删除失效时刻，epoch 毫秒，UTC；未删除为 null
 * @param endedEarlyAtUtc   提前结束操作时刻，epoch 毫秒，UTC；未提前结束为 null
 */
public record SuppressionIntervalResponse(
        String intervalId,
        String campaignId,
        String visitorId,
        long startAtUtc,
        long endAtUtc,
        long originalEndAtUtc,
        SuppressionIntervalStatus status,
        long createdAtUtc,
        long updatedAtUtc,
        Long deletedAtUtc,
        Long endedEarlyAtUtc
) {
    public static SuppressionIntervalResponse from(SuppressionInterval i) {
        return new SuppressionIntervalResponse(
                i.intervalId(),
                i.campaignId(),
                i.visitorId(),
                i.startAtUtc(),
                i.endAtUtc(),
                i.originalEndAtUtc(),
                i.status(),
                i.createdAtUtc(),
                i.updatedAtUtc(),
                i.deletedAtUtc(),
                i.endedEarlyAtUtc());
    }
}
