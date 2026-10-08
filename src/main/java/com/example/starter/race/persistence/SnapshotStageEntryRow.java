package com.example.starter.race.persistence;

import com.example.starter.race.domain.StageStatus;

/**
 * result_snapshot_stage_entry 表行记录（封榜时某选手某阶段的资格固化）。
 *
 * @param raceId                  所属快照的赛事ID
 * @param stageNo                 阶段序号
 * @param bib                     参赛号
 * @param rawTimeMs               该阶段原始用时（毫秒）；阶段不完整为 null
 * @param checkpointCount         该阶段检查点总数
 * @param coveredCheckpointCount  封榜时已覆盖检查点数量
 * @param status                  阶段资格：COMPLETE / OUT / INCOMPLETE
 */
public record SnapshotStageEntryRow(
        String raceId,
        int stageNo,
        String bib,
        Long rawTimeMs,
        int checkpointCount,
        int coveredCheckpointCount,
        StageStatus status
) {
}
