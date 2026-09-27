package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 分段通过记录响应。
 *
 * @param timingId       全局唯一分段记录ID
 * @param bib            选手参赛号
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序，从1连续递增
 * @param elapsedMillis  通过该检查点的累计耗时（毫秒）
 * @param medicalHold    是否被标记为 MEDICAL_HOLD：true-提交于医疗暂停区间，保存但不参与排名
 * @param excludedHoldId 排除该计时的医疗暂停ID；正常计时为 null
 * @param createdAt      提交时间，Unix毫秒时间戳（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointTimingResponse(
        String timingId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        boolean medicalHold,
        String excludedHoldId,
        long createdAt
) {
}
