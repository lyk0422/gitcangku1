package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RelayStandingCalculator;

/**
 * relay_finish 表行记录：末棒交接完成后自动生成的队伍接力完赛记录。
 *
 * @param raceId    所属赛事ID
 * @param teamKey   队伍标识
 * @param totalMs   接力总用时（毫秒），取末棒 elapsedMillis
 * @param foulCount 该队犯规次数；达到2次时状态为 DISQUALIFIED
 * @param status    队伍成绩状态：RANKED / DISQUALIFIED
 * @param createdAt 完赛记录生成时间，Unix毫秒时间戳
 */
public record RelayFinishRow(
        String raceId,
        String teamKey,
        long totalMs,
        int foulCount,
        EntryStatus status,
        long createdAt) implements RelayStandingCalculator.FinishView {
}
