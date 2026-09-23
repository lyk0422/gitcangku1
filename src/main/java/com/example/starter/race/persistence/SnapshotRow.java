package com.example.starter.race.persistence;

import java.util.List;

/**
 * result_snapshot 头表与其条目、分段明细、冻结事件合成的封榜快照。
 *
 * @param raceId       赛事ID
 * @param version      封榜时的赛事版本号
 * @param sealedAt     封榜时间，Unix毫秒时间戳
 * @param eventVersion 封榜时已登记的中止恢复事件总数（完整事件版本）
 * @param entries      按展示顺序排列的快照条目
 * @param checkpoints  全部选手 × 全部检查点的固化明细（缺失检查点净值为 null）
 * @param suspensions  封榜时冻结的完整中止恢复事件版本（只读）
 */
public record SnapshotRow(
        String raceId,
        int version,
        long sealedAt,
        int eventVersion,
        List<SnapshotEntryRow> entries,
        List<SnapshotCheckpointRow> checkpoints,
        List<SnapshotSuspensionRow> suspensions
) {

    /** 含条目与分段明细的构造器：事件版本为0、无冻结事件。 */
    public SnapshotRow(
            String raceId,
            int version,
            long sealedAt,
            List<SnapshotEntryRow> entries,
            List<SnapshotCheckpointRow> checkpoints) {
        this(raceId, version, sealedAt, 0, entries, checkpoints, List.of());
    }

    /** 不含分段明细的兼容构造器（未配置检查点的赛事封榜）。 */
    public SnapshotRow(String raceId, int version, long sealedAt, List<SnapshotEntryRow> entries) {
        this(raceId, version, sealedAt, 0, entries, List.of(), List.of());
    }
}
