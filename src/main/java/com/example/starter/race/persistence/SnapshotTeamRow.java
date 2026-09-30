package com.example.starter.race.persistence;

/**
 * result_snapshot_team 表行记录：封榜时固化的团队成绩头，创建后只读。
 *
 * <p>每个已锁定名单的团队一行；roster_version 是封榜时刻引用的唯一完整名单版本，
 * 榜单只能引用一个完整名单版本。
 *
 * @param raceId        所属快照的赛事ID
 * @param teamId        团队ID
 * @param rosterVersion 封榜引用的名单版本号
 * @param totalTimeMs   团队总耗时（毫秒）=版本内参与计分成员总耗时之和
 * @param memberCount   名单版本固化成员数
 * @param scoringCount  实际参与团队计分的成员数（按封榜时退赛生效状态排除）
 * @param displayOrder  展示顺序，从0开始：团队总分升序，同分按团队ID字典序
 */
public record SnapshotTeamRow(
        String raceId,
        String teamId,
        int rosterVersion,
        long totalTimeMs,
        int memberCount,
        int scoringCount,
        int displayOrder
) {
}
