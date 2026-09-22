package com.example.starter.race.persistence;

/**
 * split_time 表行记录（选手检查点通过记录，同一选手同一检查点最多一条）。
 *
 * @param timingId       分段计时ID，全局唯一（业务幂等键）
 * @param raceId         所属赛事ID
 * @param bib            选手参赛号
 * @param checkpointCode 检查点编码
 * @param seq            检查点顺序（冗余自 race_checkpoint，便于按顺序校验与展示）
 * @param elapsedMs      分段耗时（毫秒，1~86400000），按检查点顺序严格递增且小于原始完赛耗时
 * @param createdAt      记录提交时间，Unix毫秒时间戳
 */
public record SplitTimeRow(
        String timingId,
        String raceId,
        String bib,
        String checkpointCode,
        int seq,
        long elapsedMs,
        long createdAt
) {
}
