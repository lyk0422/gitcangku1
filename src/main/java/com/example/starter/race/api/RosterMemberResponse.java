package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 已锁定名单版本中单个成员的固化成绩归属（只读，退赛或后续名单变化都不改写）。
 *
 * @param bib          参赛号
 * @param rank         锁定时固化的全场名次；非 RANKED 为 null
 * @param status       锁定时固化的成绩状态
 * @param finishTimeMs 锁定时固化的原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs    锁定时固化的生效加时合计毫秒数
 * @param totalTimeMs  锁定时固化的总耗时（毫秒）；非 RANKED 为 null
 * @param memberPoints 成员名次积分；非 RANKED 为 0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RosterMemberResponse(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        int memberPoints
) {
}
