package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 一支接力队伍的逐棒明细与犯规清单。
 *
 * @param teamKey            队伍标识
 * @param status             队伍状态：RACING / RANKED / DISQUALIFIED
 * @param legs               按棒次1..N的逐棒明细
 * @param fouls              犯规记录清单（按棒次升序）
 * @param totalFouls         犯规次数
 * @param totalElapsedMillis 完赛总用时（末棒累计耗时，毫秒）；未完赛为 null
 * @param finishedAt         末棒完成时刻，Unix毫秒时间戳；未完赛为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayTeamDetailResponse(
        String teamKey,
        String status,
        List<RelayLegDetailResponse> legs,
        List<FoulResponse> fouls,
        int totalFouls,
        Long totalElapsedMillis,
        Long finishedAt) {
}
