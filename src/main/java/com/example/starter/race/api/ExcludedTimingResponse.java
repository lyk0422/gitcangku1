package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 被医疗暂停排除（MEDICAL_HOLD）的分段计时项。
 *
 * @param timingId       分段记录ID（证据不删除）
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序
 * @param elapsedMillis  通过累计耗时（毫秒）
 * @param excludedHoldId 排除该计时的医疗暂停ID
 * @param createdAt      分段提交时刻，Unix毫秒时间戳（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExcludedTimingResponse(
        String timingId,
        String checkpointCode,
        int position,
        long elapsedMillis,
        String excludedHoldId,
        long createdAt
) {
}
