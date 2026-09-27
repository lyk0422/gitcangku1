package com.example.starter.race.domain;

import java.util.List;

/**
 * 单个选手的成绩条目。
 *
 * @param bib                     参赛号
 * @param rank                    名次，从1开始；并列同名次且跳号（1、1、3）；非 RANKED 为 null
 * @param status                  成绩状态
 * @param finishTimeMs            原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs               全部未撤销加时处罚的合计毫秒数；无加时为 0
 * @param totalTimeMs             总耗时=原始完赛耗时+生效加时（毫秒）；未排名时为 null
 * @param checkpointCount         赛事配置的检查点总数；未配置检查点的赛事为 0
 * @param coveredCheckpointCount  该选手有效（未被医疗暂停排除）分段已覆盖的检查点数量
 * @param missingCheckpoints      有效分段尚未覆盖的检查点代码，按检查点顺序排列；全部覆盖或无检查点时为空列表
 * @param medicalHoldActive       计算时刻该选手是否处于生效（未恢复）医疗暂停区间
 * @param excludedTimingCount     因医疗暂停被排除（MEDICAL_HOLD）的分段计时数量；原始计时不删除，仅不计排名与覆盖
 */
public record ResultEntry(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        int checkpointCount,
        int coveredCheckpointCount,
        List<String> missingCheckpoints,
        boolean medicalHoldActive,
        int excludedTimingCount
) {

    /** 未配置检查点赛事使用的兼容构造器：检查点计数均为 0、无医疗暂停信息。 */
    public ResultEntry(
            String bib,
            Integer rank,
            EntryStatus status,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs) {
        this(bib, rank, status, finishTimeMs, penaltyMs, totalTimeMs, 0, 0, List.of(), false, 0);
    }

    /** 检查点赛事使用的兼容构造器：不带医疗暂停信息。 */
    public ResultEntry(
            String bib,
            Integer rank,
            EntryStatus status,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs,
            int checkpointCount,
            int coveredCheckpointCount,
            List<String> missingCheckpoints) {
        this(bib, rank, status, finishTimeMs, penaltyMs, totalTimeMs,
                checkpointCount, coveredCheckpointCount, missingCheckpoints, false, 0);
    }
}
