package com.example.starter.race.persistence;

import com.example.starter.race.domain.StageCheckpointView;

/**
 * stage_checkpoint 表行记录（阶段内检查点配置）。
 *
 * @param raceId         所属赛事ID
 * @param stageNo        阶段序号
 * @param checkpointCode 检查点代码，同一阶段内唯一
 * @param position       阶段内检查点顺序，从1连续递增
 * @param createdAt      配置时间，Unix毫秒时间戳
 */
public record StageCheckpointRow(
        String raceId,
        int stageNo,
        String checkpointCode,
        int position,
        long createdAt
) implements StageCheckpointView {
}
