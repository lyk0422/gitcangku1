package com.example.starter.race.persistence;

import java.util.List;

/**
 * team_snapshot 头表与其团队条目、成员明细合成的封榜团队快照。
 *
 * @param raceId   赛事ID，与个人封榜快照同版本
 * @param version  封榜后的赛事版本号
 * @param sealedAt 封榜时间，Unix毫秒时间戳
 * @param entries  按展示顺序排列的团队快照条目
 */
public record TeamSnapshotRow(
        String raceId,
        int version,
        long sealedAt,
        List<TeamSnapshotEntryRow> entries
) {
}
