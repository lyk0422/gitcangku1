package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动小时账目诊断视图（只读）：按 UTC 日给出 24 个小时的预算与占用明细。
 *
 * @param activityId   活动编号
 * @param utcDate      查询的 UTC 日，格式 yyyy-MM-dd
 * @param hours        24 个小时条目（0~23 时升序）
 * @param settledAtUtc 查询前结算过期预占所用的当前时刻，epoch 毫秒，UTC
 */
public record ActivityHoursResponse(
        String activityId,
        LocalDate utcDate,
        List<HourEntry> hours,
        long settledAtUtc
) {
    /**
     * 单个小时账目条目。
     *
     * @param hourOfDay      UTC 小时（0~23）
     * @param budget         该小时预算，单位次；未配置小时预算时为 null
     * @param reserved       尚未确认的预占占用数，单位次
     * @param confirmed      已确认数，单位次
     * @param cancelled      已释放数（取消与过期合计），单位次
     * @param remaining      该小时剩余可申请名额（budget - reserved - confirmed）；
     *                       未配置小时预算时为 null
     */
    public record HourEntry(
            int hourOfDay,
            Integer budget,
            int reserved,
            int confirmed,
            int cancelled,
            Integer remaining
    ) {
    }
}
