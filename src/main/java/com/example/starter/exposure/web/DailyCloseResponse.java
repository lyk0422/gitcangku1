package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动日结快照视图：固化某 UTC 日每小时确认数、取消数与过期数。
 * 计数来自历史固化的活动小时账目，后续配置变更与公告迁移均不改写已结算日。
 *
 * @param activityId     活动编号
 * @param utcDate        结算的 UTC 日，格式 yyyy-MM-dd
 * @param hours          恰好 24 个小时快照，按下标对应 UTC 小时 0~23
 * @param confirmedTotal 当日确认总数，单位次
 * @param cancelledTotal 当日取消总数，单位次
 * @param expiredTotal   当日过期总数，单位次
 */
public record DailyCloseResponse(
        String activityId,
        LocalDate utcDate,
        List<HourClose> hours,
        int confirmedTotal,
        int cancelledTotal,
        int expiredTotal
) {
    /**
     * 单小时日结快照。
     *
     * @param hourOfDay UTC 小时，0~23
     * @param confirmed 该小时确认数，单位次，历史固化
     * @param cancelled 该小时取消数，单位次，历史固化
     * @param expired   该小时过期数，单位次，历史固化
     */
    public record HourClose(int hourOfDay, int confirmed, int cancelled, int expired) {
    }
}
