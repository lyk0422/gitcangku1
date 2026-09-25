package com.example.starter.race.persistence;

import com.example.starter.race.domain.RelayTeamStatus;

import java.util.List;

/**
 * relay_finish 表行记录：末棒交接完成后固化的队伍接力完赛记录。
 *
 * @param raceId             所属接力赛事ID
 * @param teamKey            完赛队伍标识
 * @param legCount           完赛时固化的棒次数
 * @param legElapsedMillis   各棒次累计耗时（毫秒），按棒次1..N
 * @param foulLegs           犯规棒次清单（接棒棒次，升序）
 * @param totalFouls         犯规次数
 * @param totalElapsedMillis 总用时=末棒累计耗时（毫秒）
 * @param status             完赛成绩状态：RANKED / DISQUALIFIED
 * @param finishedAt         末棒交接完成时刻，Unix毫秒时间戳
 * @param createdAt          完赛记录生成时间，Unix毫秒时间戳
 */
public record RelayFinishRow(
        String raceId,
        String teamKey,
        int legCount,
        List<Long> legElapsedMillis,
        List<Integer> foulLegs,
        int totalFouls,
        long totalElapsedMillis,
        RelayTeamStatus status,
        long finishedAt,
        long createdAt) {
}
