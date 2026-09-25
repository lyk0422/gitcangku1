package com.example.starter.race.domain;

/**
 * 接力排名条目：一支队伍在即时排名或封榜快照中的展示行。
 *
 * @param teamKey   队伍标识
 * @param rank      名次（从1开始，并列同名次并跳号）；未排名为 null
 * @param status    RANKED-参与排名；UNTIMED-尚未完赛；DISQUALIFIED-犯规达2次取消资格
 * @param totalMs   接力总用时（毫秒，末棒 elapsedMillis）；未完赛/取消资格为 null
 * @param foulCount 犯规次数；大于0时单独标注但不取消资格（除非达到2次）
 */
public record RelayStanding(
        String teamKey,
        Integer rank,
        EntryStatus status,
        Long totalMs,
        int foulCount) {

    /** 是否存在犯规标注（犯规次数大于0）。 */
    public boolean hasFouls() {
        return foulCount > 0;
    }
}
