package com.example.starter.race.persistence;

import com.example.starter.race.domain.ScoringMode;

import java.util.List;

/**
 * result_snapshot 头表与其条目、分段明细合成的封榜快照。
 *
 * @param raceId      赛事ID
 * @param version     封榜时的版本号
 * @param scoringMode 封榜时冻结的计分模式
 * @param sealedAt    封榜时间，Unix毫秒时间戳
 * @param entries     按展示顺序排列的快照条目
 * @param checkpoints 全部选手 × 全部检查点的固化明细（缺失检查点 elapsedMillis/timingId 为 null）
 */
public record SnapshotRow(
        String raceId,
        int version,
        ScoringMode scoringMode,
        long sealedAt,
        List<SnapshotEntryRow> entries,
        List<SnapshotCheckpointRow> checkpoints
) {
}
