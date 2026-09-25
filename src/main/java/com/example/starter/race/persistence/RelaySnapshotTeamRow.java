package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

/**
 * relay_snapshot_team 表行记录：封榜时固化的队伍最终名次与犯规标注。
 *
 * @param raceId       所属快照的赛事ID
 * @param teamKey      队伍标识
 * @param rank         名次（从1开始，并列跳号）；未完赛/取消资格为 null
 * @param status       队伍成绩状态：RANKED / UNTIMED / DISQUALIFIED
 * @param totalMs      接力总用时（毫秒）；未排名为 null
 * @param foulCount    犯规次数，存在犯规的队伍以此标注
 * @param displayOrder 展示顺序，从0开始
 */
public record RelaySnapshotTeamRow(
        String raceId,
        String teamKey,
        Integer rank,
        EntryStatus status,
        Long totalMs,
        int foulCount,
        int displayOrder) {
}
