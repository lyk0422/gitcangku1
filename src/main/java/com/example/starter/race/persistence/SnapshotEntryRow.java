package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

import java.util.List;

/**
 * result_snapshot_entry 表行记录（封榜只读快照中的单条成绩）。
 *
 * @param raceId                    所属快照的赛事ID
 * @param bib                       参赛号
 * @param rank                      名次；非 RANKED 为 null
 * @param status                    成绩状态
 * @param finishTimeMs              原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs                 生效加时合计毫秒数
 * @param totalTimeMs               总耗时毫秒数；未排名为 null
 * @param displayOrder              展示顺序，从0开始
 * @param checkpointCount           赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount    该选手已覆盖检查点数量
 * @param missingCheckpoints        缺失检查点代码，按检查点顺序排列；无缺失时为空列表
 * @param lastCheckpointCode        封榜时最后通过（顺序最大）的检查点代码；无分段记录为 null
 */
public record SnapshotEntryRow(
        String raceId,
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        int displayOrder,
        int checkpointCount,
        int coveredCheckpointCount,
        List<String> missingCheckpoints,
        String lastCheckpointCode
) {

    /** 兼容旧调用的构造器：检查点计数为 0、缺失列表为空、无最后通过检查点。 */
    public SnapshotEntryRow(
            String raceId,
            String bib,
            Integer rank,
            EntryStatus status,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs,
            int displayOrder) {
        this(raceId, bib, rank, status, finishTimeMs, penaltyMs, totalTimeMs, displayOrder,
                0, 0, List.of(), null);
    }

    /** 兼容旧调用的构造器：最后通过检查点为 null。 */
    public SnapshotEntryRow(
            String raceId,
            String bib,
            Integer rank,
            EntryStatus status,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs,
            int displayOrder,
            int checkpointCount,
            int coveredCheckpointCount,
            List<String> missingCheckpoints) {
        this(raceId, bib, rank, status, finishTimeMs, penaltyMs, totalTimeMs, displayOrder,
                checkpointCount, coveredCheckpointCount, missingCheckpoints, null);
    }
}
