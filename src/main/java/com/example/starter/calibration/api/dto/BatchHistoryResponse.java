package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批次历史响应：统计重算历史与放行快照，均按发生顺序升序，只读不改写。
 *
 * @param batchKey 业务批次键
 * @param stats    统计重算历史
 * @param releases 放行快照
 */
public record BatchHistoryResponse(
        String batchKey,
        List<StatsItem> stats,
        List<SnapshotItem> releases) {

    /**
     * 统计重算记录项。includedCount 为 0 时 meanValue/minValue/maxValue 为 null。
     *
     * @param id            统计记录 ID
     * @param revision      重算时的测量修订版本
     * @param reason        重算原因：CREATE / SUBMIT / EXCLUDE
     * @param readingCount  读数总数（含已排除）
     * @param includedCount 计入统计的读数条数
     * @param excludedCount 已排除的读数条数
     * @param meanValue     计入读数均值（十进制字符串）；无计入读数为 null
     * @param minValue      计入读数最小值；无计入读数为 null
     * @param maxValue      计入读数最大值；无计入读数为 null
     * @param createdAt     重算时间（UTC）
     */
    public record StatsItem(
            long id,
            int revision,
            String reason,
            int readingCount,
            int includedCount,
            int excludedCount,
            String meanValue,
            String minValue,
            String maxValue,
            Instant createdAt) {
    }

    /**
     * 放行快照项。后续测量修订不改写该快照。
     *
     * @param id            快照 ID
     * @param revision      放行时的测量修订版本
     * @param certificateId 放行时绑定的证书 ID（证书版本）
     * @param releasedBy    放行人
     * @param includedCount 放行时计入统计的读数条数
     * @param meanValue     放行时计入读数均值（十进制字符串）；无计入读数为 null
     * @param releasedAt    放行时间（UTC）
     */
    public record SnapshotItem(
            long id,
            int revision,
            long certificateId,
            String releasedBy,
            int includedCount,
            String meanValue,
            Instant releasedAt) {
    }
}
