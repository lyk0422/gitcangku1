package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

import java.util.List;

/**
 * result_snapshot_entry 表行记录（封榜只读快照中的单条成绩）。
 *
 * @param raceId                   所属快照的赛事ID
 * @param bib                      参赛号
 * @param rank                     名次；非 RANKED 为 null
 * @param status                   成绩状态
 * @param finishTimeMs             原始枪声完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs                生效加时合计毫秒数
 * @param totalTimeMs              枪声总耗时毫秒数（原始完赛+生效加时）；未排名为 null
 * @param waveKey                  封榜时所属波次唯一键；无波次参赛者为 null
 * @param waveStartMs              封榜时所属波次 UTC 起跑时刻（Unix 毫秒时间戳）；无波次为 null
 * @param baseStartMs              封榜时赛事基准起跑时刻（Unix 毫秒时间戳）；无波次或基准缺失为 null
 * @param netTimeMs                最终净计时（毫秒）；负净计时等非 RANKED 情况为 null
 * @param invalidReason            非排名原因：INVALID_WAVE-净计时为负；正常排名或其它状态为 null
 * @param displayOrder             展示顺序，从0开始
 * @param checkpointCount          赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount   该选手已覆盖检查点数量
 * @param missingCheckpoints       缺失检查点代码，按检查点顺序排列；无缺失时为空列表
 */
public record SnapshotEntryRow(
        String raceId,
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
        int displayOrder,
        int checkpointCount,
        int coveredCheckpointCount,
        List<String> missingCheckpoints
) {
}
