package com.example.starter.race.persistence;

import com.example.starter.race.domain.RunnerRaceState;

/**
 * runner_race_state 表行记录（选手起跑/退赛状态）。
 *
 * @param raceId      所属赛事ID
 * @param bib         选手参赛号
 * @param state       状态：REGISTERED/STARTED/WITHDRAWN
 * @param startedAt   起跑时间，Unix毫秒时间戳；未起跑为 null
 * @param withdrawnAt 退赛时间，Unix毫秒时间戳；未退赛为 null
 * @param reason      退赛原因；未退赛为 null
 * @param createdAt   状态行创建时间，Unix毫秒时间戳
 * @param updatedAt   最近状态变更时间，Unix毫秒时间戳
 */
public record RunnerRaceStateRow(
        String raceId,
        String bib,
        RunnerRaceState state,
        Long startedAt,
        Long withdrawnAt,
        String reason,
        long createdAt,
        long updatedAt
) {
}
