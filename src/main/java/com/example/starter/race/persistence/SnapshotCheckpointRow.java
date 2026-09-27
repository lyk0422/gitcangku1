package com.example.starter.race.persistence;

/**
 * result_snapshot_checkpoint 表行记录：封榜快照中某选手在某检查点的固化明细。
 *
 * @param raceId         所属快照的赛事ID
 * @param bib            参赛号
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序，从1递增
 * @param elapsedMillis  封榜时通过耗时（毫秒）；缺失检查点为 null
 * @param timingId       分段记录ID；缺失检查点为 null
 * @param excludedHoldId 排除该分段的医疗暂停ID；非 null 表示 MEDICAL_HOLD 被排除；null 为正常/缺失
 * @param excludedReason 分段被排除的原因（固化的医疗暂停原因）；未排除为 null
 */
public record SnapshotCheckpointRow(
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        Long elapsedMillis,
        String timingId,
        String excludedHoldId,
        String excludedReason
) {

    /** 不含医疗暂停排除信息的兼容构造器（正常或缺失检查点）。 */
    public SnapshotCheckpointRow(
            String raceId,
            String bib,
            String checkpointCode,
            int position,
            Long elapsedMillis,
            String timingId) {
        this(raceId, bib, checkpointCode, position, elapsedMillis, timingId, null, null);
    }
}
