package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

/**
 * category_snapshot_entry 表行记录（组别封榜只读快照中的单条成绩）。
 *
 * @param raceId       所属快照的赛事ID
 * @param categoryCode 所属快照的组别代码
 * @param bib          参赛号
 * @param rank         组内名次；非 RANKED 为 null
 * @param status       成绩状态
 * @param finishTimeMs 原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs    生效加时合计毫秒数
 * @param totalTimeMs  总耗时毫秒数；未排名为 null
 * @param displayOrder 组内展示顺序，从0开始
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