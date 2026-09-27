package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动日结只读视图。查询往日时首次固化每小时确认数与取消数快照；快照一旦固化不再改写，
 * 后续活动配置变更或公告迁移都不影响已结算日。查询当日返回实时账目（不固化）。
 *
 * @param activityId          活动编号
 * @param utcDate             结算的 UTC 日（左闭右开的整日）
 * @param frozen              本响应是否来自已固化快照；当日实时视图为 false
 * @param settledAtUtc        固化时刻，epoch 毫秒，UTC；未固化（实时视图）为 null
 * @param hours               固定 24 项小时结算，按小时升序
 * @param dailyConfirmedCount 当日确认合计，单位次
 * @param dailyCancelledCount 当日取消合计，单位次
 */
public record DailySettlementResponse(
        String activityId,
        LocalDate utcDate,
        boolean frozen,
        Long settledAtUtc,
        List<Hour> hours,
        int dailyConfirmedCount,
        int dailyCancelledCount
) {
    /**
     * 单个 UTC 小时结算快照。
     *
     * @param utcHour        UTC 小时（0～23），区间 [utcHour:00, utcHour+1:00)
     * @param confirmedCount 该小时确认数（固化值或实时值），单位次
     * @param cancelledCount 该小时取消数（固化值或实时值），单位次
     */
    public record Hour(
            int utcHour,
            int confirmedCount,
            int cancelledCount
    ) {
    }
}
