package com.example.starter.race.persistence;

/**
 * result_snapshot_team_member 表行记录：封榜时固化的团队成员与计分依据。
 *
 * @param raceId        所属快照的赛事ID
 * @param teamCode      所属团队代码
 * @param bib           成员参赛号
 * @param scoring       封榜时是否入选团队计分（前3名 RANKED 成员）
 * @param scoringTimeMs 入选者的计分值（毫秒，即该选手封榜时含有效处罚的总耗时）；未入选为 null
 * @param displayOrder  展示顺序，从0开始，按参赛号字典序
 */
public record SnapshotTeamMemberRow(
        String raceId,
        String teamCode,
        String bib,
        boolean scoring,
        Long scoringTimeMs,
        int displayOrder
) {
}
