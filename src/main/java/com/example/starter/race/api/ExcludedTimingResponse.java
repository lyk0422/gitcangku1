package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 被排除计时明细（医疗暂停诊断用）。原始记录保留，但不参与排名。
 *
 * @param timingId        分段记录ID
 * @param checkpointCode  检查点代码
 * @param position        检查点顺序
 * @param elapsedMillis   通过累计耗时（毫秒）
 * @param exclusionReason 排除原因：MEDICAL_HOLD
 * @param createdAt       提交时间，UTC毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExcludedTimingResponse(
        String timingId,
        String checkpointCode,
        int position,
        long elapsedMillis,
        String exclusionReason,
        long createdAt
) {
}
