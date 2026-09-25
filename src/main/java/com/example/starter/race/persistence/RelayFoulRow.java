package com.example.starter.race.persistence;

/**
 * relay_foul 表行记录：一次交接区超时犯规；不可逆，一队同一交接仅一条。
 *
 * @param id             自增主键
 * @param raceId         所属接力赛事ID
 * @param teamKey        犯规队伍标识
 * @param legNo          犯规交接棒次（接棒选手棒次）
 * @param handoffMillis  该次交接区实际用时（毫秒）
 * @param limitMillis    判定时赛事的交接区上限（毫秒）
 * @param createdAt      犯规记录时间（交接完成时刻），Unix毫秒时间戳
 */
public record RelayFoulRow(
        long id,
        String raceId,
        String teamKey,
        int legNo,
        long handoffMillis,
        int limitMillis,
        long createdAt) {
}
