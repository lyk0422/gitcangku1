package com.example.starter.race.persistence;

import java.util.List;

/**
 * 接力封榜快照：头表 + 全部队伍名次行 + 全部逐棒明细行。
 *
 * @param raceId   赛事ID
 * @param version  封榜后的赛事版本号
 * @param sealedAt 封榜时间，Unix毫秒时间戳
 * @param teams    队伍名次行，按展示顺序排列
 * @param legs     全部队伍逐棒明细行
 */
public record RelaySnapshotRow(
        String raceId,
        int version,
        long sealedAt,
        List<RelaySnapshotTeamRow> teams,
        List<RelaySnapshotLegRow> legs) {
}
