package com.example.starter.race.persistence;

import java.util.List;

/**
 * 组别封榜快照：头表行加全部固化条目。
 *
 * @param raceId       所属赛事ID
 * @param categoryCode 组别代码
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳
 * @param entries      封榜时刻所属该组别的在赛选手成绩条目，按展示顺序排列
 */
public record CategorySnapshotRow(
        String raceId,
        String categoryCode,
        long sealedAt,
        List<CategorySnapshotEntryRow> entries
) {
}
