package com.example.starter.race.persistence;

import java.util.List;

/**
 * category_snapshot 头表与其条目合成的组别封榜快照。
 *
 * @param raceId       赛事ID
 * @param categoryCode 组别代码
 * @param version      组别封榜时的赛事版本号
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳
 * @param entries      按展示顺序排列的快照条目
 */
public record CategorySnapshotRow(
        String raceId,
        String categoryCode,
        int version,
        long sealedAt,
        List<CategorySnapshotEntryRow> entries
) {
}
