package com.example.starter.race.persistence;

/**
 * result_snapshot_checkpoint 表行记录：封榜快照中某选手在某检查点的固化明细。
 *
 * @param raceId           所属快照的赛事ID
 * @param bib              参赛号
 * @param checkpointCode   检查点代码
 * @param position         检查点顺序，从1递增
 * @param elapsedMillis    封榜时原始通过耗时（毫秒）；缺失检查点为 null
 * @param netElapsedMillis 封榜时净通过耗时（原始分段扣除中止补偿，毫秒）；缺失检查点为 null
 * @param timingId         分段记录ID；缺失检查点为 null
 */
public record SnapshotCheckpointRow(
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        Long elapsedMillis,
        Long netElapsedMillis,
        String timingId
) {

    /** 兼容旧调用的构造器：净耗时等于原始耗时。 */
    public SnapshotCheckpointRow(
            String raceId,
            String bib,
            String checkpointCode,
            int position,
            Long elapsedMillis,
            String timingId) {
        this(raceId, bib, checkpointCode, position, elapsedMillis, elapsedMillis, timingId);
    }
}
