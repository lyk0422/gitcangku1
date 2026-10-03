package com.example.starter.exposure.domain;

import java.time.LocalDate;

/**
 * 活动某 UTC 日某小时的额度账本。used 为当前占用数（RESERVED 与 CONFIRMED 合计）；
 * confirmed/cancelled 为该小时已结算的确认数与取消（含过期）数快照，日结查询直接读取，
 * 后续配置变更不改写已结算数据。
 *
 * @param activityId 活动编号
 * @param utcDate    所属 UTC 日
 * @param hourOfDay  小时（0~23，UTC），区间左闭右开 [hour:00, hour+1:00)
 * @param used       当前占用数，单位次，不小于 0
 * @param confirmed  该小时累计确认数快照，单位次
 * @param cancelled  该小时累计取消/过期数快照，单位次
 */
public record ActivityHourLedger(
        String activityId,
        LocalDate utcDate,
        int hourOfDay,
        int used,
        int confirmed,
        int cancelled
) {
}