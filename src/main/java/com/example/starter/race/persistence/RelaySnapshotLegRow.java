package com.example.starter.race.persistence;

/**
 * relay_snapshot_leg 表行记录：封榜时固化的队伍逐棒明细（含犯规标记）。
 *
 * @param raceId    所属快照的赛事ID
 * @param teamKey   队伍标识
 * @param legNo     棒次序号，从1开始
 * @param runner    该棒次登记选手标识
 * @param elapsedMs 该棒次结束时累计用时（毫秒）；未交接为 null
 * @param splitMs   该棒次分段用时（毫秒）；无法计算为 null
 * @param zoneMs    进入该棒次的交接区用时（毫秒，棒次>=2）；未交接/首棒为 null
 * @param foul      进入该棒次的交接是否犯规
 */
public record RelaySnapshotLegRow(
        String raceId,
        String teamKey,
        int legNo,
        String runner,
        Long elapsedMs,
        Long splitMs,
        Long zoneMs,
        boolean foul) {
}
