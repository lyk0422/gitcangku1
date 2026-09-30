package com.example.starter.race.persistence;

import java.util.List;

/**
 * 某组别的封榜只读快照：头部 + 全部成绩条目。
 *
 * @param raceId       所属赛事ID
 * @param categoryCode 组别代码
 * @param version      封榜后的赛事版本号
 * @param sealedAt     封榜时间，Unix毫秒时间戳
 * @param entries      按展示顺序排列的成绩条目
 */
public record CategorySnapshotRow(
        String raceId,
        String categoryCode,
        int version,
        long sealedAt,
        List<CategorySnapshotEntryRow> entries
) {
}
