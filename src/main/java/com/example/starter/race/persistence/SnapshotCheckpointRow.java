package com.example.starter.race.persistence;

/**
 * result_snapshot_checkpoint 表行记录：封榜快照中某选手在某检查点的固化明细。
 *
 * @param raceId         所属快照的赛事ID
 * @param bib            参赛号
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序，从1递增
 * @param elapsedMillis  封榜时原始通过累计耗时（毫秒）；缺失检查点为 null
 * @param netElapsedMs   封榜时净分段累计耗时（毫秒）；缺失检查点为 null，无中止事件时等于原始值
 * @param compensationMs 该检查点累计补偿毫秒数，未受影响为 0
 * @param timingId       分段记录ID；缺失检查点为 null
 */
public record SnapshotCheckpointRow(
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        Long elapsedMillis,
        Long netElapsedMs,
        long compensationMs,
        String timingId
) {

    /** 兼容无中止事件场景的构造器：净值等于原始值、补偿为0。 */
    public SnapshotCheckpointRow(
            String raceId,
            String bib,
            String checkpointCode,
            int position,
            Long elapsedMillis,
            String timingId) {
        this(raceId, bib, checkpointCode, position, elapsedMillis, elapsedMillis, 0L, timingId);
    }
}
