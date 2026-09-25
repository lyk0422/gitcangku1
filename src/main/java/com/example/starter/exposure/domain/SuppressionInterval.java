package com.example.starter.exposure.domain;

/**
 * 访客抑制区间 PO。区间以 UTC 半开时间 {@code [startAtUtc, endAtUtc)} 表示，
 * 左闭右开：当前时刻等于 startAtUtc 命中抑制，等于 endAtUtc 不再命中。
 *
 * <p>{@code endAtUtc} 为当前生效结束时刻：已开始的区间只能提前结束（缩短），
 * 缩短后不得早于操作时刻；{@code originalEndAtUtc} 保存创建时计划结束时刻且永不变更。
 * 未开始区间被删除时状态置为 DELETED，字段保持删除时快照且不再变更。</p>
 *
 * @param intervalId        区间编号
 * @param campaignId        所属公告编号
 * @param visitorId         被抑制访客编号；命中时对该公告所有展示位生效
 * @param startAtUtc        生效开始时刻，epoch 毫秒，UTC，左闭
 * @param endAtUtc          当前生效结束时刻，epoch 毫秒，UTC，右开；提前结束时缩短
 * @param originalEndAtUtc  创建时计划结束时刻，epoch 毫秒，UTC，不可变
 * @param status            区间状态（ACTIVE/DELETED）
 * @param createdAtUtc      创建时刻，epoch 毫秒，UTC
 * @param updatedAtUtc      最近变更时刻，epoch 毫秒，UTC
 * @param deletedAtUtc      删除失效时刻，epoch 毫秒，UTC；未删除为 null
 * @param endedEarlyAtUtc   提前结束操作时刻，epoch 毫秒，UTC；未提前结束为 null
 */
public record SuppressionInterval(
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
    /** 判断给定时刻是否命中半开区间：startAtUtc &lt;= now &lt; endAtUtc。 */
    public boolean contains(long nowUtc) {
        return startAtUtc <= nowUtc && nowUtc < endAtUtc;
    }
}
