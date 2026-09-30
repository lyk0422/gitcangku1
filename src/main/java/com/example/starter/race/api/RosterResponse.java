package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 团队名单视图：当前名单或某一历史锁定版本。
 *
 * <p>当前名单反映最新锁定版本及之后的退赛状态；锁定版本查询原样返回封版内容，
 * 不随成员退赛或后续版本变化而改写。
 *
 * @param raceId          赛事ID
 * @param teamId          团队ID
 * @param rosterVersion   名单版本号，从1开始
 * @param lockedAt        锁定时间，Unix毫秒时间戳
 * @param members         版本固化时的成员列表（按参赛号字典序）
 * @param current         是否为该队当前名单；历史版本查询为 false
 * @param withdrawals     该版本成员已登记的退赛信息（按登记时间排列）；历史版本返回空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RosterResponse(
        String raceId,
        String teamId,
        int rosterVersion,
        long lockedAt,
        List<RosterMemberResponse> members,
        boolean current,
        List<WithdrawalResponse> withdrawals
) {
}
