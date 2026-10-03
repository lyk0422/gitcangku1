package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

import java.util.List;

/**
 * result_snapshot_entry 表行记录。
 *
 * @param raceId                  所属快照的赛事ID
 * @param bib                     参赛号
 * @param categoryCode            封榜时刻所属组别代码；未分组为 null，历史快照不追溯重写
 * @param rank                    名次（从1开始，并列同名次并跳号）；未排名为 null
 * @param status                  成绩状态
 * @param finishTimeMs            原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs               生效加时处罚合计毫秒数
 * @param totalTimeMs             总耗时（毫秒）；未排名为 null
 * @param displayOrder            展示顺序，从0开始
 * @param checkpointCount         赛事检查点总数
 * @param coveredCheckpointCount  封榜时已覆盖检查点数量
 * @param missingCheckpoints      缺失检查点代码（由快照明细派生）
 */
public record SnapshotEntryRow(
        String raceId,
        String bib,
        String categoryCode,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        int displayOrder,
        int checkpointCount,
        int coveredCheckpointCount,
        List<String> missingCheckpoints
) {
}