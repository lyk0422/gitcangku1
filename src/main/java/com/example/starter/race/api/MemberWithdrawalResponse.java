package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 成员退赛登记结果；只记录退赛生效场次与原因，不从任何已锁定名单删除成员。
 *
 * @param raceId           赛事ID
 * @param teamId           所属团队ID
 * @param bib              退赛成员参赛号
 * @param state            固定为 WITHDRAWN
 * @param firstVersionNo   成员首次入队的名单版本号
 * @param effectiveSession 退赛具体生效场次代码
 * @param reason           退赛原因
 * @param withdrawnAt      退赛记录时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberWithdrawalResponse(
        String raceId,
        String teamId,
        String bib,
        String state,
        int firstVersionNo,
        String effectiveSession,
        String reason,
        Long withdrawnAt
) {
}
