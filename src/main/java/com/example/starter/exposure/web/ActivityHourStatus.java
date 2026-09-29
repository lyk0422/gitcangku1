package com.example.starter.exposure.web;

/**
 * 活动某 UTC 小时名额诊断视图（只读）。小时区间为左闭右开 [startAtUtc, endAtUtc)。
 *
 * @param utcHour     UTC 小时（0～23）
 * @param startAtUtc  小时起点，epoch 毫秒，UTC
 * @param endAtUtc    小时终点（下一小时起点），epoch 毫秒，UTC
 * @param configured  当日是否存在活动配置；false 时预算与剩余均为 0，该小时不可申请
 * @param budget      该小时预算，单位次；未配置为 0
 * @param used        该小时当前占用名额（RESERVED 与 CONFIRMED 合计），单位次
 * @param remaining   该小时剩余名额（budget - used），单位次；未配置为 0
 */
public record ActivityHourStatus(
        int utcHour,
        long startAtUtc,
        long endAtUtc,
        boolean configured,
        int budget,
        int used,
        int remaining
) {
}
