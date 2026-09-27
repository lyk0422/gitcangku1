package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单条医疗暂停历史项（不可变证据视图）：开始事件必有，恢复信息在恢复后出现。
 *
 * @param holdId             医疗暂停ID
 * @param bib                选手参赛号
 * @param startAt            暂停开始时刻，Unix毫秒时间戳（UTC），左闭端点
 * @param reason             暂停原因
 * @param startedBy          登记开始的医疗角色ID
 * @param createdAt          开始登记提交时刻，Unix毫秒时间戳（UTC）
 * @param active             是否仍生效（true=未恢复）
 * @param endAt              暂停结束时刻，Unix毫秒时间戳（UTC），右端点（不含）；生效中为 null
 * @param durationMs         实际暂停时长=endAt-startAt（毫秒）；生效中为 null
 * @param resumedBy          确认适赛的医疗角色ID；生效中为 null
 * @param fitConclusion      适赛结论；生效中为 null
 * @param resumedAt          恢复登记提交时刻，Unix毫秒时间戳（UTC）；生效中为 null
 * @param excludedTimingCount 该暂停区间内被排除（MEDICAL_HOLD）的分段计时数量
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicalHoldHistoryItem(
        String holdId,
        String bib,
        long startAt,
        String reason,
        String startedBy,
        long createdAt,
        boolean active,
        Long endAt,
        Long durationMs,
        String resumedBy,
        String fitConclusion,
        Long resumedAt,
        int excludedTimingCount
) {
}
