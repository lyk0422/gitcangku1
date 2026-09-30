package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

/**
 * team_roster_member 表行记录：名单版本锁定时固化的单个成员成绩归属。
 *
 * @param raceId        所属赛事ID
 * @param teamId        所属团队ID
 * @param versionNo     所属名单版本号
 * @param bib           参赛号
 * @param rankNo        锁定时固化的全场名次；非 RANKED 为 null
 * @param entryStatus   锁定时固化的个人成绩状态
 * @param finishTimeMs  锁定时固化的原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs     锁定时固化的生效加时合计毫秒数
 * @param totalTimeMs   锁定时固化的总耗时（毫秒）；非 RANKED 为 null
 * @param memberPoints  该成员贡献的名次积分；非 RANKED 为 0
 * @param displayOrder  名单内展示顺序，从0开始
 */
public record TeamRosterMemberRow(
        String raceId,
        String teamId,
        int versionNo,
        String bib,
        Integer rankNo,
        EntryStatus entryStatus,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        int memberPoints,
        int displayOrder
) {
}
