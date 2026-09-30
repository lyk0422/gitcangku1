package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 名单中的单个成员；成绩字段为该成员所在名单版本锁定时刻固化的个人成绩快照，
 * 不随后续计时修订、处罚变更或退赛改写（历史版本查询据此与当前成绩区分）。
 *
 * @param bib              参赛号
 * @param rosterVersion    加入名单时的版本号（该成员首次被锁定的版本）
 * @param entryStatus      版本固化的个人成绩状态：RANKED / UNTIMED / MISSING_CHECKPOINT / DISQUALIFIED
 * @param finishTimeMs     版本固化的原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs        版本固化的生效加时合计毫秒数
 * @param totalTimeMs      版本固化的总耗时（毫秒）；未排名为 null
 * @param withdrawn        当前是否已退赛（历史版本查询恒为 false，原样返回封版内容）
 * @param effectiveSession 退赛生效场次号（从1开始的正整数）；未退赛为 null
 * @param reason           退赛原因；未退赛为 null
 * @param withdrawnAt      退赛登记时间，Unix毫秒时间戳；未退赛为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RosterMemberResponse(
        String bib,
        int rosterVersion,
        EntryStatus entryStatus,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        boolean withdrawn,
        Integer effectiveSession,
        String reason,
        Long withdrawnAt
) {
}
