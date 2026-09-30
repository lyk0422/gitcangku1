package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 当前团队名单中的成员视图：在最新锁定版本固化成绩归属之上叠加当前在队状态。
 *
 * @param bib              参赛号
 * @param state            ACTIVE-在队计分；WITHDRAWN-已退赛，未封榜后续计分剔除
 * @param firstVersionNo   首次入队（被锁定）的名单版本号
 * @param currentVersionNo 当前所属（最新锁定）名单版本号
 * @param effectiveSession 退赛生效场次代码；未退赛为 null
 * @param reason           退赛原因；未退赛为 null
 * @param withdrawnAt      退赛记录时间，Unix毫秒时间戳；未退赛为 null
 * @param memberPoints     在当前版本中固化的名次积分；非 RANKED 为 0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentRosterMemberResponse(
        String bib,
        String state,
        int firstVersionNo,
        int currentVersionNo,
        String effectiveSession,
        String reason,
        Long withdrawnAt,
        int memberPoints
) {
}
