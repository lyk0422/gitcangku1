package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 申诉受理时冻结的选手原始/净成绩视图。
 *
 * @param leaderboardVersion 受理时冻结的榜单（赛事）版本
 * @param finishTimeMs       冻结的原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs          冻结的生效加时合计毫秒数
 * @param totalTimeMs        冻结的净成绩总耗时（原始+加时，毫秒）；未排名为 null
 * @param rank               冻结的名次；未排名为 null
 * @param status             冻结的成绩状态 RANKED/UNTIMED/MISSING_CHECKPOINT/DISQUALIFIED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FrozenResultResponse(
        int leaderboardVersion,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        Integer rank,
        EntryStatus status
) {
}
