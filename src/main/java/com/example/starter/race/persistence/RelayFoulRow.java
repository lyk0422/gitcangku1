package com.example.starter.race.persistence;

/**
 * relay_foul 表行记录：交接区犯规记录，不可逆，每队每交接至多一条。
 *
 * @param raceId    所属赛事ID
 * @param teamKey   犯规队伍标识
 * @param legNo     犯规发生的交接棒次
 * @param zoneMs    交接区实际用时（毫秒）
 * @param limitMs   判犯规时生效的交接区上限（毫秒）
 * @param createdAt 犯规记录时间，Unix毫秒时间戳
 */
public record RelayFoulRow(
        String raceId,
        String teamKey,
        int legNo,
        long zoneMs,
        long limitMs,
        long createdAt) {
}
