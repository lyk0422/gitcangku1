package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

/**
 * result_snapshot_team_member 表行记录：封榜时随团队成绩一起固化的成员个人成绩，只读。
 *
 * <p>数值与“是否计分/是否退赛生效”均为封榜时刻状态；成员之后退赛不重算已封榜积分。
 *
 * @param raceId           所属快照的赛事ID
 * @param teamId           团队ID
 * @param bib              参赛号
 * @param entryStatus      名单版本固化的个人成绩状态
 * @param finishTimeMs     名单版本固化的原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs        名单版本固化的生效加时合计毫秒数
 * @param totalTimeMs      名单版本固化的总耗时（毫秒）；未排名为 null
 * @param scoring          是否计入团队总分：仅 RANKED 且封榜时未处于退赛生效状态
 * @param withdrawn        封榜时刻是否已处于退赛生效状态
 * @param effectiveSession 退赛生效场次号；封榜时未退赛为 null
 */
public record SnapshotTeamMemberRow(
        String raceId,
        String teamId,
        String bib,
        EntryStatus entryStatus,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        boolean scoring,
        boolean withdrawn,
        Integer effectiveSession
) {
}
