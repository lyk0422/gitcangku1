package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 团队计分中的成员个人成绩；数值锁定自名单版本固化时刻，不随后续计时/处罚/退赛改写历史榜单。
 *
 * @param bib              参赛号
 * @param status           锁定时刻的个人成绩状态：RANKED / UNTIMED / MISSING_CHECKPOINT / DISQUALIFIED
 * @param finishTimeMs     锁定时刻原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs        锁定时刻生效加时合计毫秒数
 * @param totalTimeMs      锁定时刻总耗时（毫秒）；未排名为 null，且不计入团队总分
 * @param scoring          是否计入团队总分：仅 RANKED 成员计入
 * @param withdrawn        当前（实时榜）或封榜时刻（快照榜）是否处于退赛生效状态
 * @param effectiveSession 退赛生效场次号；未退赛为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamMemberScoreResponse(
        String bib,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        boolean scoring,
        boolean withdrawn,
        Integer effectiveSession
) {
}
