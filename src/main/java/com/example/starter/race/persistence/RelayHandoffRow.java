package com.example.starter.race.persistence;

/**
 * relay_handoff 表行记录：一次成功交接（接棒棒次≥2）。
 *
 * @param id             自增主键
 * @param raceId         所属接力赛事ID
 * @param teamKey        交接队伍标识
 * @param legNo          交接棒次（接棒选手棒次）
 * @param bib            该棒次接棒选手参赛号
 * @param elapsedMillis  接棒选手累计耗时（毫秒），严格递增
 * @param handoffMillis  交接区实际用时（毫秒）
 * @param foul           本次交接是否犯规
 * @param completedAt    交接完成的服务端时刻，Unix毫秒时间戳
 * @param createdAt      交接提交时间，Unix毫秒时间戳
 */
public record RelayHandoffRow(
        long id,
        String raceId,
        String teamKey,
        int legNo,
        String bib,
        long elapsedMillis,
        long handoffMillis,
        boolean foul,
        long completedAt,
        long createdAt) {
}
