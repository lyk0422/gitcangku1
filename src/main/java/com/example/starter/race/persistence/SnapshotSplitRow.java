package com.example.starter.race.persistence;

/**
 * result_snapshot_split 表行记录（封榜快照中固化的单选手单检查点分段明细）。
 *
 * @param raceId         所属快照的赛事ID
 * @param bib            参赛号
 * @param checkpointCode 检查点编码
 * @param seq            检查点顺序，从1开始连续递增
 * @param elapsedMs      分段耗时（毫秒）；null 表示该检查点缺失（漏点）
 */
public record SnapshotSplitRow(
        String raceId,
        String bib,
        String checkpointCode,
        int seq,
        Long elapsedMs
) {
}
