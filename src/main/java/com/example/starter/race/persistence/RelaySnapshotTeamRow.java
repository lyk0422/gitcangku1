package com.example.starter.race.persistence;

import com.example.starter.race.domain.RelayTeamStatus;

import java.util.List;

/**
 * relay_snapshot_team 表行记录：封榜快照中一支队伍的逐棒明细与最终名次。
 *
 * @param raceId             所属接力快照赛事ID
 * @param teamKey            队伍标识
 * @param rankNo             名次（从1开始，并列同名次并跳号）；未完赛/取消资格为 null
 * @param status             队伍状态：RACING / RANKED / DISQUALIFIED
 * @param legCount           棒次数
 * @param legBibs            各棒次登记选手参赛号，按棒次1..N
 * @param legElapsedMillis   各棒次累计耗时（毫秒）；未交接棒次为 null
 * @param legHandoffMillis   各交接棒次交接区用时（毫秒）；首棒/未交接为 null
 * @param legCompletedAt     各棒次交接完成服务端时刻（Unix毫秒）；首棒/未交接为 null
 * @param foulLegs           犯规棒次清单（接棒棒次，升序）
 * @param totalFouls         犯规次数
 * @param totalElapsedMillis 总用时=末棒累计耗时（毫秒）；未完赛为 null
 * @param finishedAt         末棒完成时刻，Unix毫秒时间戳；未完赛为 null
 * @param displayOrder       展示顺序，从0开始
 */
public record RelaySnapshotTeamRow(
        String raceId,
        String teamKey,
        Integer rankNo,
        RelayTeamStatus status,
        int legCount,
        List<String> legBibs,
        List<Long> legElapsedMillis,
        List<Long> legHandoffMillis,
        List<Long> legCompletedAt,
        List<Integer> foulLegs,
        int totalFouls,
        Long totalElapsedMillis,
        Long finishedAt,
        int displayOrder) {
}
