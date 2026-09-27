package com.example.starter.race.api;

/**
 * 被医疗暂停排除的分段计时诊断视图。
 *
 * @param timingId       全局唯一分段记录ID
 * @param bib            选手参赛号
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序
 * @param elapsedMillis  通过该检查点的累计耗时（毫秒）
 * @param holdId         导致排除的医疗暂停ID
 * @param reason         排除原因（固化自医疗暂停原因）
 * @param createdAt      分段记录提交时间，Unix毫秒时间戳
 */
public record ExcludedTimingResponse(
        String timingId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        String holdId,
        String reason,
        long createdAt
) {
}
