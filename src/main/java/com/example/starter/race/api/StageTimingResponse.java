package com.example.starter.race.api;

/**
 * 阶段分段记录响应。
 *
 * @param timingId       全局唯一阶段分段记录ID
 * @param stageNo        阶段序号
 * @param bib            选手参赛号
 * @param checkpointCode 检查点代码
 * @param position       阶段内检查点顺序
 * @param elapsedMillis  阶段内累计耗时（毫秒）
 * @param createdAt      提交时间，Unix毫秒时间戳
 */
public record StageTimingResponse(
        String timingId,
        int stageNo,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        long createdAt
) {
}
