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
 * @param totalTimeMs               总耗时展示值毫秒数（让分赛为 HALF_UP 舍入值）；未排名为 null
 * @param displayOrder              展示顺序，从0开始
 * @param checkpointCount           赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount    该选手已覆盖检查点数量
 * @param missingCheckpoints        缺失检查点代码，按检查点顺序排列；无缺失时为空列表
 * @param factorP                   封榜时冻结的让分系数分子 p（约分后）；非让分赛或未登记为 null
 * @param factorQ                   封榜时冻结的让分系数分母 q（约分后）；非让分赛或未登记为 null
 * @param scoreNumerator            封榜时冻结的精确有效成绩分子（约分后）；非让分赛或未排名为 null
 * @param scoreDenominator          封榜时冻结的精确有效成绩分母（约分后）；非让分赛或未排名为 null
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
        Integer factorP,
        Integer factorQ,
        Long scoreNumerator,
        Long scoreDenominator
) {

    /** 标准计分赛事的兼容构造器：无让分冻结字段。 */
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
