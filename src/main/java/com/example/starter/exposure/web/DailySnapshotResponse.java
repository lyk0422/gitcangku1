package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动日结视图：固化某 UTC 日每小时确认数与取消数快照。
 *
 * <p>该 UTC 日彻底终结（最后一个小时的预占也全部到期，即次日 00:01 UTC 之后）
 * 首次查询时一次事务固化 24 行；{@code frozen=true} 表示读自固化快照，
 * 后续配置变更或任何操作都不再改写。当天或尚未终结时 {@code frozen=false}，
 * 返回实时统计值（不作为最终日结）。</p>
 *
 * @param activityId     活动编号
 * @param utcDate        日结所属 UTC 日，格式 yyyy-MM-dd
 * @param frozen         是否已固化；true 为不可变快照，false 为实时值
 * @param generatedAtUtc 本次查询/固化时刻，epoch 毫秒，UTC
 * @param hours          按小时升序的 24 行确认数与取消数
 */
public record DailySnapshotResponse(
        String activityId,
        LocalDate utcDate,
        boolean frozen,
        long generatedAtUtc,
        List<HourCount> hours
) {
    /**
     * 单个 UTC 小时的日结计数（单位次，均为实际值）。
     *
     * @param hour           UTC 小时序号（0～23，区间左闭右开）
     * @param confirmedCount 该小时固化（或实时）的确认数
     * @param cancelledCount 该小时固化（或实时）的主动取消数
     */
    public record HourCount(int hour, int confirmedCount, int cancelledCount) {
    }
}
