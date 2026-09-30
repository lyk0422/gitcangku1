package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 日逐小时名额诊断视图。
 *
 * @param activityId    活动编号
 * @param utcDate       查询的 UTC 日，格式 yyyy-MM-dd
 * @param configured    该日是否已配置小时预算
 * @param dailyTotalCap 该日总确认额度，单位次；未配置为 null
 * @param version       配置版本；未配置为 null
 * @param usedTotal     该日各小时已占用名额合计，单位次
 * @param hours         24 个小时明细（下标即小时 0..23）
 * @param queriedAtUtc  查询时刻，epoch 毫秒，UTC
 */
public record ActivityDayQuotaResponse(
        String activityId,
        LocalDate utcDate,
        boolean configured,
        Integer dailyTotalCap,
        Long version,
        int usedTotal,
        List<ActivityHourQuota> hours,
        long queriedAtUtc
) {
    /**
     * 单个小时的名额明细。
     *
     * @param hourOfDay    小时序号，取值 0..23
     * @param hourStartUtc 小时起点，epoch 毫秒，UTC，区间左闭右开
     * @param budget       该小时预算，单位次；未配置为 null
     * @param used         该小时已占用名额（RESERVED 与 CONFIRMED 合计），单位次
     * @param remaining    该小时剩余名额，单位次；未配置为 null
     */
    public record ActivityHourQuota(
            int hourOfDay,
            long hourStartUtc,
            Integer budget,
            int used,
            Integer remaining
    ) {
    }
}
