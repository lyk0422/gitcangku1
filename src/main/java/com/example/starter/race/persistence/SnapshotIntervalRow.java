package com.example.starter.race.persistence;

/**
 * result_snapshot_interval 表行记录（封榜时固化的区间记录，只读）。
 *
 * @param raceId         所属快照的赛事ID
 * @param bib            参赛号
 * @param checkpointCode 计时点代码；完赛区间固定为 FINISH
 * @param position       计时点顺序，检查点从1递增，完赛最后
 * @param lowerMs        封榜时固化的区间下界（毫秒）
 * @param upperMs        封榜时固化的区间上界（毫秒）
 * @param timingId       来源区间记录ID
 */
public record SnapshotIntervalRow(
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        long lowerMs,
        long upperMs,
        String timingId
) {
}
