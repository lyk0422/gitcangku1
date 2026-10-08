package com.example.starter.race.persistence;

/**
 * result_snapshot_stage 表行记录（封榜时某阶段的最快原始用时与完整人数固化）。
 *
 * @param raceId        所属快照的赛事ID
 * @param stageNo       阶段序号
 * @param fastestMs     封榜时该阶段全部完整选手的最快原始用时F（毫秒）
 * @param completeCount 封榜时该阶段完整选手数量
 */
public record SnapshotStageRow(String raceId, int stageNo, long fastestMs, int completeCount) {
}
