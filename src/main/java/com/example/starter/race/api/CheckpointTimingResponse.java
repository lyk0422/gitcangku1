package com.example.starter.race.api;

/**
 * 分段通过记录响应。
 *
 * @param timingId       全局唯一分段记录ID
 * @param bib            选手参赛号
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序，从1连续递增
 * @param elapsedMillis  通过该检查点的累计耗时（毫秒）
 * @param createdAt      提交时间，Unix毫秒时间戳
 */
public record CheckpointTimingResponse(
        String timingId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        long createdAt
) {
}
