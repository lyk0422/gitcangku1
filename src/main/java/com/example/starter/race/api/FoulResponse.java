package com.example.starter.race.api;

/**
 * 一条交接区超时犯规记录。
 *
 * @param teamKey        犯规队伍标识
 * @param legNo          犯规交接棒次（接棒选手棒次）
 * @param handoffMillis  该次交接区实际用时（毫秒）
 * @param limitMillis    判定时赛事的交接区上限（毫秒）
 * @param createdAt      犯规记录时间（交接完成时刻），Unix毫秒时间戳
 */
public record FoulResponse(
        String teamKey,
        int legNo,
        long handoffMillis,
        int limitMillis,
        long createdAt) {
}
