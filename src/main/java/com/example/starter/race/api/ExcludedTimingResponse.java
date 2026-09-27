package com.example.starter.race.api;

/**
 * 被排除出排名的分段计时诊断明细。
 *
 * @param timingId        分段记录ID
 * @param checkpointCode  检查点代码
 * @param position        检查点顺序
 * @param elapsedMillis   通过该检查点的累计耗时（毫秒）
 * @param exclusionReason 排除原因（MEDICAL_HOLD-医疗暂停生效期间提交）
 * @param holdId          导致排除的生效医疗暂停ID
 * @param createdAt       分段提交时间，Unix毫秒时间戳
 */
public record ExcludedTimingResponse(
        String timingId,
        String checkpointCode,
        int position,
        long elapsedMillis,
        String exclusionReason,
        String holdId,
        long createdAt
) {
}
