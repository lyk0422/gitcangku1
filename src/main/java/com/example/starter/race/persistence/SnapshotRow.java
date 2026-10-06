package com.example.starter.race.persistence;

import com.example.starter.race.domain.RankingMode;

import java.util.List;

/**
 * result_snapshot 头表与其条目、分段明细合成的封榜快照。
 *
 * @param raceId      赛事ID
 * @param version     封榜时的版本号
 * @param rankingMode 封榜时固化的排名模式
 * @param sealedAt    封榜时间，Unix毫秒时间戳
 * @param entries     按展示顺序排列的快照条目
 * @param checkpoints 全部选手 × 全部检查点的固化明细（缺失检查点 elapsedMillis/timingId 为 null）
 */
public record SnapshotRow(
        String raceId,
        int version,
        RankingMode rankingMode,
        long sealedAt,
        List<SnapshotEntryRow> entries,
        List<SnapshotCheckpointRow> checkpoints
) {

    /** 不含分段明细的兼容构造器（未配置检查点的 TOTAL_TIME 赛事封榜）。 */
    public SnapshotRow(String raceId, int version, long sealedAt, List<SnapshotEntryRow> entries) {
        this(raceId, version, RankingMode.TOTAL_TIME, sealedAt, entries, List.of());
    }
}
