package com.example.starter.maintenance;

import java.time.Instant;
import java.util.List;

/**
 * 冲突簇合并/裁决快照响应：返回快照状态、胜出版本（如有）、固化的证据与维护窗口版本。
 *
 * @param snapshotId          快照主键
 * @param clusterId           冲突簇标识
 * @param stationId           所属观测站标识
 * @param sequence            簇内快照序号
 * @param status              MERGED / PENDING_MANUAL
 * @param winnerObservationId 自动合并胜出观测标识；PENDING_MANUAL 时为 null
 * @param evidenceCount       证据条数（实际值）
 * @param affectedCount       其中受维护影响条数
 * @param createdAtUtc        快照生成时刻（UTC）
 * @param evidence            全部证据（含维护标记）
 * @param windows             快照固化的维护窗口版本
 */
public record SnapshotResponse(
        long snapshotId,
        String clusterId,
        String stationId,
        int sequence,
        String status,
        String winnerObservationId,
        int evidenceCount,
        int affectedCount,
        Instant createdAtUtc,
        List<EvidenceResponse> evidence,
        List<SnapshotWindowResponse> windows) {

    /**
     * 由快照领域对象构造响应。
     */
    public static SnapshotResponse of(ClusterSnapshotRecord record) {
        List<EvidenceResponse> evidence = record.evidence().stream().map(EvidenceResponse::of).toList();
        List<SnapshotWindowResponse> windows = record.windows().stream().map(SnapshotWindowResponse::of).toList();
        int affected = (int) record.evidence().stream().filter(SnapshotEvidence::maintenanceAffected).count();
        return new SnapshotResponse(
                record.snapshotId(),
                record.clusterId(),
                record.stationId(),
                record.sequence(),
                record.status().name(),
                record.winnerObservationId(),
                record.evidence().size(),
                affected,
                record.createdAtUtc(),
                evidence,
                windows);
    }
}
