package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

import java.util.List;

/**
 * result_snapshot_entry 表行记录（封榜只读快照中的单条成绩）。
 *
 * @param raceId                    所属快照的赛事ID
 * @param bib                       参赛号
 * @param rank                      名次（单值模式）；非 RANKED 或区间模式为 null
 * @param status                    成绩状态
 * @param finishTimeMs              原始完赛耗时（毫秒，单值模式）；计时缺失或区间模式为 null
 * @param penaltyMs                 生效加时合计毫秒数
 * @param totalTimeMs               总耗时毫秒数（单值模式）；未排名或区间模式为 null
 * @param displayOrder              展示顺序，从0开始
 * @param checkpointCount           赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount    该选手已覆盖检查点数量
 * @param missingCheckpoints        缺失检查点代码，按检查点顺序排列；无缺失时为空列表
 * @param rankBest                  区间模式：保证最好名次；单值模式或非完整选手为 null
 * @param rankWorst                 区间模式：可能最差名次；单值模式或非完整选手为 null
 * @param netLowerMs                区间模式：净区间下界（毫秒）；非 RANKED 为 null
 * @param netUpperMs                区间模式：净区间上界（毫秒）；非 RANKED 为 null
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
        Integer rankBest,
        Integer rankWorst,
        Long netLowerMs,
        Long netUpperMs
) {

    /** 单值模式构造器：区间字段均为 null。 */
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
                checkpointCount, coveredCheckpointCount, missingCheckpoints,
                null, null, null, null);
    }
}
