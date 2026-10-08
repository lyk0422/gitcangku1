package com.example.starter.race.persistence;

import com.example.starter.race.domain.StageTimingView;

/**
 * stage_timing 表行记录（选手在某阶段通过某检查点的分段记录，阶段从零计时）。
 *
 * @param timingId       全局唯一阶段分段记录ID
 * @param raceId         所属赛事ID
 * @param stageNo        阶段序号
 * @param bib            选手参赛号
 * @param checkpointCode 检查点代码
 * @param position       阶段内检查点顺序（配置时固化）
 * @param elapsedMillis  阶段内累计耗时（毫秒，1~86400000）
 * @param createdAt      提交时间，Unix毫秒时间戳
 */
public record StageTimingRow(
        String timingId,
        String raceId,
        int stageNo,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        long createdAt
) implements StageTimingView {
}
