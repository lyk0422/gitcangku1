package com.example.starter.maintenance;

import java.time.Instant;
import java.util.List;

/**
 * 冲突簇合并快照：一次合并/裁决判定的不可变历史，固化当时覆盖的观测证据与维护窗口版本。
 * 同一冲突簇重复合并会追加新序号快照，历史快照永不修改。
 *
 * @param snapshotId          快照主键（自增）
 * @param clusterId           冲突簇标识
 * @param stationId           所属观测站标识
 * @param sequence           该冲突簇内的快照序号，从 1 开始单调递增
 * @param status             快照状态：MERGED / PENDING_MANUAL
 * @param winnerObservationId 自动合并胜出的观测标识；PENDING_MANUAL 时为 null
 * @param createdAtUtc       快照生成时刻（UTC）
 * @param evidence           簇内全部观测证据（按提交时刻升序，平局按观测标识升序）
 * @param windows            快照固化的维护窗口版本摘要
 */
public record ClusterSnapshotRecord(
        long snapshotId,
        String clusterId,
        String stationId,
        int sequence,
        SnapshotStatus status,
        String winnerObservationId,
        Instant createdAtUtc,
        List<SnapshotEvidence> evidence,
        List<SnapshotWindow> windows) {
}
