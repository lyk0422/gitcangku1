package com.example.starter.race.persistence;

import java.util.List;

/**
 * result_snapshot 头表与其条目、分段明细、圈次明细合成的封榜快照。
 *
 * @param raceId      赛事ID
 * @param version     封榜时的版本号
 * @param sealedAt    封榜时间，Unix毫秒时间戳
 * @param entries     按展示顺序排列的快照条目
 * @param checkpoints 全部选手 × 全部检查点的固化明细（标准赛事；缺失检查点
 *                    elapsedMillis/timingId 为 null）
 * @param laps        绕圈赛事封榜时固化的圈次当前记录；标准赛事为空列表
 */
public record SnapshotRow(
        String raceId,
        int version,
        long sealedAt,
        List<SnapshotEntryRow> entries,
        List<SnapshotCheckpointRow> checkpoints,
        List<SnapshotLapRow> laps
) {

    /** 不含圈次明细的兼容构造器（标准赛事封榜）。 */
    public SnapshotRow(
            String raceId,
            int version,
            long sealedAt,
            List<SnapshotEntryRow> entries,
            List<SnapshotCheckpointRow> checkpoints) {
        this(raceId, version, sealedAt, entries, checkpoints, List.of());
    }

    /** 不含分段与圈次明细的兼容构造器（未配置检查点的标准赛事封榜）。 */
    public SnapshotRow(String raceId, int version, long sealedAt, List<SnapshotEntryRow> entries) {
        this(raceId, version, sealedAt, entries, List.of(), List.of());
    }
}