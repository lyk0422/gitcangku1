package com.example.starter.race.domain;

import java.util.List;

/**
 * 单个选手的成绩条目。
 *
 * @param bib                     参赛号
 * @param rank                    名次，从1开始；并列同名次且跳号（1、1、3）；非 RANKED 为 null
 * @param status                  成绩状态
 * @param finishTimeMs            原始枪声完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs               全部未撤销加时处罚的合计毫秒数；无加时为 0
 * @param totalTimeMs             枪声总耗时=原始完赛耗时+生效加时（毫秒）；未排名时为 null
 * @param waveKey                 所属波次唯一键；无波次参赛者为 null
 * @param waveStartMs             所属波次 UTC 起跑时刻（Unix 毫秒时间戳）；无波次为 null
 * @param baseStartMs             赛事基准起跑时刻（Unix 毫秒时间戳）；无波次或基准缺失为 null
 * @param netTimeMs               最终净计时（毫秒）：有波次=枪声总耗时-(波次起跑-基准起跑)，
 *                                无波次=枪声总耗时；非 RANKED 为 null
 * @param invalidReason           非排名原因：INVALID_WAVE-波次净计时为负；正常排名或其它状态为 null
 * @param checkpointCount         赛事配置的检查点总数；未配置检查点的赛事为 0
 * @param coveredCheckpointCount  该选手已有分段记录的检查点数量
 * @param missingCheckpoints      该选手尚未通过的检查点代码，按检查点顺序排列；全部覆盖或无检查点时为空列表
 */
public record ResultEntry(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        String waveKey,
        Long waveStartMs,
        Long baseStartMs,
        Long netTimeMs,
        String invalidReason,
        int checkpointCount,
        int coveredCheckpointCount,
        List<String> missingCheckpoints
) {

    /** 未配置检查点、无波次赛事使用的兼容构造器：波次字段为 null、检查点计数为 0、缺失列表为空。 */
    public ResultEntry(
            String bib,
            Integer rank,
            EntryStatus status,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs) {
        this(bib, rank, status, finishTimeMs, penaltyMs, totalTimeMs,
                null, null, null,
                status == EntryStatus.RANKED ? totalTimeMs : null,
                null, 0, 0, List.of());
    }

    /** 检查点能力使用的兼容构造器：波次字段为 null，净计时沿用枪声总耗时。 */
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
                null, null, null,
                status == EntryStatus.RANKED ? totalTimeMs : null,
                null, checkpointCount, coveredCheckpointCount, missingCheckpoints);
    }
}
