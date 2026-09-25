package com.example.starter.exposure.domain;

/**
 * 抑制区间不可变删除记录 PO。仅追加插入，不更新、不删除；
 * 每个未开始区间被删除时对应一条，保留审计轨迹。
 *
 * @param recordId      删除记录编号
 * @param intervalId    被删除的抑制区间编号
 * @param campaignId    所属公告编号
 * @param visitorId     被抑制访客编号
 * @param startAtUtc    区间原计划开始时刻，epoch 毫秒，UTC
 * @param endAtUtc      区间原计划结束时刻，epoch 毫秒，UTC，右开
 * @param deletedAtUtc  删除操作时刻，epoch 毫秒，UTC
 * @param requestId     触发删除的写操作幂等键
 */
public record SuppressionDeleteRecord(
        String recordId,
        String intervalId,
        String campaignId,
        String visitorId,
        long startAtUtc,
        long endAtUtc,
        long deletedAtUtc,
        String requestId
) {
}
