package com.example.starter.plan.web.dto;

/**
 * 单条生效接续的实时保障评估（诊断用）。
 * sufficient = deficitMinutes &lt;= 0，即实际接续分钟数满足要求。
 */
public record ConnectionDeficitView(
        long connectionId,
        String predecessorScheduleKey,
        int guaranteeMinutes,
        long actualMinutes,
        long deficitMinutes,
        boolean sufficient) {
}
