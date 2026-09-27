package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 分段通过记录响应。
 *
 * @param timingId        全局唯一分段记录ID
 * @param bib             选手参赛号
 * @param checkpointCode  检查点代码
 * @param position        检查点顺序，从1连续递增
 * @param elapsedMillis   通过该检查点的累计耗时（毫秒）
 * @param exclusionReason 计时排除原因；null 表示正常计时，MEDICAL_HOLD 表示暂停期间被排除、不参与排名
 * @param createdAt       提交时间，UTC毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointTimingResponse(
        String timingId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        String exclusionReason,
        long createdAt
) {
}
