package com.example.starter.race.persistence;

import java.util.List;

/**
 * result_snapshot 头表与其条目、分段明细、三源读数依据合成的封榜快照。
 *
 * @param raceId         赛事ID
 * @param version        封榜时的版本号
 * @param sealedAt       封榜时间，Unix毫秒时间戳
 * @param entries        按展示顺序排列的快照条目
 * @param checkpoints    全部选手 × 全部检查点的固化明细（缺失检查点 elapsedMillis/timingId 为 null）
 * @param sensorReadings 三源检查点封榜时冻结的三条读数依据（无三源点为空列表）
 */
public record SnapshotRow(
        String raceId,
        int version,
        long sealedAt,
        List<SnapshotEntryRow> entries,
        List<SnapshotCheckpointRow> checkpoints,
        List<SnapshotSensorReadingRow> sensorReadings
) {

    /** 不含分段明细的兼容构造器（未配置检查点的赛事封榜）。 */
    public SnapshotRow(String raceId, int version, long sealedAt, List<SnapshotEntryRow> entries) {
        this(raceId, version, sealedAt, entries, List.of(), List.of());
    }

    /** 不含三源读数依据的兼容构造器（无三源检查点的赛事封榜）。 */
    public SnapshotRow(String raceId, int version, long sealedAt,
                       List<SnapshotEntryRow> entries, List<SnapshotCheckpointRow> checkpoints) {
        this(raceId, version, sealedAt, entries, checkpoints, List.of());
    }
}
