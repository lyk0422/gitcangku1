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
 * @param totalTimeMs               最终枪声计时毫秒数=原始完赛耗时+生效加时；未排名为 null
 * @param displayOrder              展示顺序，从0开始
 * @param checkpointCount           赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount    该选手已覆盖检查点数量
 * @param missingCheckpoints        缺失检查点代码，按检查点顺序排列；无缺失时为空列表
 * @param waveKey                   封榜时所属波次唯一键；无波次为 null
 * @param waveStartAt               封榜时所属波次UTC起跑时刻，Unix毫秒时间戳；无波次为 null
 * @param baseStartAt               封榜时赛事基准起跑时刻，Unix毫秒UTC时间戳
 * @param gunTimeMs                 最终枪声计时（毫秒）；无枪声计时为 null
 * @param netTimeMs                 最终净计时（毫秒）；INVALID_WAVE 或无枪声计时为 null
 * @param invalidReason             无效原因：INVALID_WAVE-净计时为负；其余为 null
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
        String waveKey,
        Long waveStartAt,
        long baseStartAt,
        Long gunTimeMs,
        Long netTimeMs,
        String invalidReason
) {

    /** 兼容旧调用的构造器：检查点计数为 0、缺失列表为空、无波次信息。 */
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
                0, 0, List.of(), null, null, 0L, totalTimeMs, totalTimeMs, null);
    }
}
