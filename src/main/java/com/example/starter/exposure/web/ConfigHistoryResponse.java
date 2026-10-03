package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动配置历史视图：每次成功修改一条快照，按版本升序。只读。
 *
 * @param activityId 活动编号
 * @param utcDate    配置所属 UTC 日，格式 yyyy-MM-dd
 * @param entries    历史条目，按版本升序
 */
public record ConfigHistoryResponse(
        String activityId,
        LocalDate utcDate,
        List<Entry> entries
) {
    /**
     * 单条配置历史。
     *
     * @param version       配置版本
     * @param dailyTotalCap 该版本日总确认额度，单位次
     * @param hourlyBudgets 该版本 24 个小时预算，下标即小时，单位次
     * @param requestId     触发本次修改的幂等键
     * @param changedAtUtc  修改时刻，epoch 毫秒，UTC
     */
    public record Entry(
            int version,
            int dailyTotalCap,
            List<Integer> hourlyBudgets,
            String requestId,
            long changedAtUtc
    ) {
    }
}