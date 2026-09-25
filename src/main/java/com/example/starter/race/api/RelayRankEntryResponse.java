package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 接力排名中的单个队伍条目。
 *
 * @param teamKey            队伍标识
 * @param rank               名次（从1开始，并列同名次并跳号）；未完赛/取消资格为 null
 * @param status             队伍状态：RACING / RANKED / DISQUALIFIED
 * @param foul               是否存在至少一次犯规（仅标注，不取消资格）
 * @param totalFouls         犯规次数
 * @param totalElapsedMillis 完赛总用时（末棒累计耗时，毫秒）；未完赛为 null
 * @param finishedAt         末棒完成时刻，Unix毫秒时间戳；未完赛为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayRankEntryResponse(
        String teamKey,
        Integer rank,
        String status,
        boolean foul,
        int totalFouls,
        Long totalElapsedMillis,
        Long finishedAt) {
}
