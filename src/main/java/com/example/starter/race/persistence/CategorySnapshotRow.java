package com.example.starter.race.persistence;

import java.util.List;

/**
 * category_snapshot 表行记录（含全部条目）。
 *
 * @param raceId       所属赛事ID
 * @param categoryCode 组别代码
 * @param version      组别封榜后的赛事版本号
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳
 * @param entries      按展示顺序排列的组内成绩条目
 */
public record CategorySnapshotRow(
        String raceId,
        String categoryCode,
        int version,
        long sealedAt,
        List<CategorySnapshotEntryRow> entries
) {
}