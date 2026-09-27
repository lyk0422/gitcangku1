package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

import java.util.List;

/**
 * result_snapshot_entry 表行记录（封榜只读快照中的单条成绩）。
 *
 * @param raceId                    所属快照的赛事ID
 * @param bib                       参赛号
 * @param rank                      名次；非 RANKED 为 null
 * @param status                    成绩状态（封榜时固化的资格状态）
 * @param finishTimeMs              原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs                 生效加时合计毫秒数
 * @param totalTimeMs               总耗时毫秒数；未排名为 null
 * @param displayOrder              展示顺序，从0开始
 * @param checkpointCount           赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount    有效（未被医疗暂停排除）分段覆盖的检查点数量
 * @param missingCheckpoints        有效分段未覆盖的检查点代码，按检查点顺序排列；无缺失时为空列表
 * @param medicalHoldActive         封榜时刻是否处于生效医疗暂停区间
 * @param excludedTimingCount       因医疗暂停被排除（MEDICAL_HOLD）的分段计时数量
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
        boolean medicalHoldActive,
        int excludedTimingCount
) {

    /** 兼容旧调用的构造器：检查点计数为 0、缺失列表为空、无医疗暂停信息。 */
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
                0, 0, List.of(), false, 0);
    }

    /** 检查点赛事使用的兼容构造器：不带医疗暂停信息。 */
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
                checkpointCount, coveredCheckpointCount, missingCheckpoints, false, 0);
    }
}
