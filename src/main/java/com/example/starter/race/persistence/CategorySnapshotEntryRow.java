package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

/**
 * category_snapshot_entry 表行记录：组别封榜时固化的单选手成绩条目。
 *
 * @param raceId       所属快照的赛事ID
 * @param categoryCode 所属快照的组别代码
 * @param bib          参赛号（封榜时刻所属该组别的在赛选手）
 * @param rank         名次；未排名为 null
 * @param status       成绩状态
 * @param finishTimeMs 原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs    生效加时合计毫秒数，无加时为 0
 * @param totalTimeMs  总耗时（毫秒）；未排名时为 null
 * @param displayOrder 展示顺序，从 0 开始
 */
public record CategorySnapshotEntryRow(
        String raceId,
        String categoryCode,
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        int displayOrder
) {
}
