package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

/**
 * result_snapshot_entry 表行记录（封榜只读快照中的单条成绩）。
 *
 * @param raceId       所属快照的赛事ID
 * @param bib          参赛号
 * @param rank         名次；UNTIMED/DISQUALIFIED 为 null
 * @param status       成绩状态
 * @param finishTimeMs 原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs    生效加时合计毫秒数
 * @param totalTimeMs  总耗时毫秒数；未排名为 null
 * @param displayOrder 展示顺序，从0开始
 */
public record SnapshotEntryRow(
        String raceId,
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        int displayOrder
) {
}
