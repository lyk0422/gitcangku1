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
 * @param totalTimeMs             总耗时=原始完赛耗时+生效加时（即最终枪声计时，毫秒）；未排名时为 null
 * @param checkpointCount         赛事配置的检查点总数；未配置检查点的赛事为 0
 * @param coveredCheckpointCount  该选手已有分段记录的检查点数量
 * @param missingCheckpoints      该选手尚未通过的检查点代码，按检查点顺序排列；全部覆盖或无检查点时为空列表
 * @param waveKey                 所属波次唯一键；不属于任何波次为 null
 * @param waveStartAt             所属波次UTC起跑时刻，Unix毫秒时间戳；无波次为 null
 * @param baseStartAt             赛事基准起跑时刻，Unix毫秒UTC时间戳
 * @param gunTimeMs               最终枪声计时（毫秒）=原始完赛耗时+生效加时；无枪声计时（未计时/取消资格）为 null
 * @param netTimeMs               最终净计时（毫秒）=枪声计时-波次起跑相对基准的毫秒差，无波次时等于枪声计时；INVALID_WAVE 或无枪声计时为 null
 * @param invalidReason           无效原因：INVALID_WAVE-净计时为负；正常或非该原因为 null
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
        String waveKey,
        Long waveStartAt,
        long baseStartAt,
        Long gunTimeMs,
        Long netTimeMs,
        String invalidReason
) {

    /** 无波次、无检查点信息的兼容构造器。 */
    public ResultEntry(
            String bib,
            Integer rank,
            EntryStatus status,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs) {
        this(bib, rank, status, finishTimeMs, penaltyMs, totalTimeMs, 0, 0, List.of(),
                null, null, 0L, totalTimeMs, totalTimeMs, null);
    }

    /** 携带检查点信息、无波次的兼容构造器。 */
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
                checkpointCount, coveredCheckpointCount, missingCheckpoints,
                null, null, 0L, totalTimeMs, totalTimeMs, null);
    }
}
