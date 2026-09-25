package com.example.starter.race.api;

import java.util.List;

/**
 * 接力犯规清单响应。
 *
 * @param raceId 赛事ID
 * @param fouls  全部犯规记录，按记录时间与棒次排列
 */
public record RelayFoulListResponse(String raceId, List<FoulEntry> fouls) {

    /**
     * 单条犯规记录（不可逆）。
     *
     * @param teamKey    犯规队伍标识
     * @param leg        犯规发生的交接棒次
     * @param zoneMillis 交接区实际用时（毫秒）
     * @param limitMillis 判犯规时生效的交接区上限（毫秒）
     * @param recordedAt 犯规记录时间，Unix毫秒时间戳
     */
    public record FoulEntry(
            String teamKey,
            int leg,
            long zoneMillis,
            long limitMillis,
            long recordedAt
    ) {
    }
}
