package com.example.starter.race.persistence;

/**
 * relay_handoff 表行记录：一次交接提交，每队每交接棒次仅一条。
 *
 * @param raceId            所属赛事ID
 * @param teamKey           队伍标识
 * @param legNo             交接棒次（接棒选手棒次，2~legCount）
 * @param receiver          接棒选手标识
 * @param elapsedMs         接棒选手累计用时（毫秒）
 * @param zoneMs            交接区实际用时（毫秒，非负）
 * @param foul              本次交接是否犯规（交接区用时超上限）
 * @param serverCompletedAt 交接完成的服务端时刻，Unix毫秒时间戳
 */
public record RelayHandoffRow(
        String raceId,
        String teamKey,
        int legNo,
        String receiver,
        long elapsedMs,
        long zoneMs,
        boolean foul,
        long serverCompletedAt) {
}
