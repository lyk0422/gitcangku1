package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 选手起跑/退赛状态响应。
 *
 * @param raceId      赛事ID
 * @param bib         参赛号
 * @param state       状态：REGISTERED-未起跑，STARTED-已起跑，WITHDRAWN-已退赛
 * @param startedAt   起跑时间，Unix毫秒时间戳；未起跑为 null
 * @param withdrawnAt 退赛时间，Unix毫秒时间戳；未退赛为 null
 * @param reason      退赛原因；未退赛为 null
 * @param updatedAt   最近状态变更时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerRaceStateResponse(
        String raceId,
        String bib,
        String state,
        Long startedAt,
        Long withdrawnAt,
        String reason,
        long updatedAt
) {
}
