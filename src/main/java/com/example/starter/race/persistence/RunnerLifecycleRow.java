package com.example.starter.race.persistence;

import com.example.starter.race.domain.RunnerLifecycleStatus;

/**
 * runner_lifecycle 表行记录；缺失行按 REGISTERED 且未起跑处理。
 *
 * @param raceId     所属赛事ID
 * @param bib        选手参赛号
 * @param status     生命周期状态 REGISTERED / STARTED / WITHDRAWN
 * @param startedAt  起跑时刻，Unix毫秒时间戳；未起跑为 null
 * @param createdAt  生命周期行创建时间，Unix毫秒时间戳
 * @param updatedAt  最近状态变更时间，Unix毫秒时间戳
 */
public record RunnerLifecycleRow(
        String raceId,
        String bib,
        RunnerLifecycleStatus status,
        Long startedAt,
        long createdAt,
        long updatedAt
) {
}
