package com.example.starter.race.persistence;

/**
 * result_snapshot_stage_checkpoint 表行记录（封榜时某选手某阶段某检查点的原始点固化）。
 *
 * @param raceId         所属快照的赛事ID
 * @param stageNo        阶段序号
 * @param bib            参赛号
 * @param checkpointCode 检查点代码
 * @param position       阶段内检查点顺序
 * @param elapsedMillis  封榜时通过该检查点的累计耗时（毫秒）；缺失检查点为 null
 * @param timingId       阶段分段记录ID；缺失检查点为 null
 */
public record SnapshotStageCheckpointRow(
        String raceId,
        int stageNo,
        String bib,
        String checkpointCode,
        int position,
        Long elapsedMillis,
        String timingId
) {
}
