package com.example.starter.race.persistence;

import com.example.starter.race.domain.TimingExclusionReason;

/**
 * result_snapshot_checkpoint 表行记录：封榜快照中某选手在某检查点的固化明细。
 *
 * @param raceId          所属快照的赛事ID
 * @param bib             参赛号
 * @param checkpointCode  检查点代码
 * @param position        检查点顺序，从1递增
 * @param elapsedMillis   封榜时通过耗时（毫秒）；缺失检查点为 null
 * @param timingId        分段记录ID；缺失检查点为 null
 * @param exclusionReason 计时排除原因；null 表示正常计时或缺失检查点，
 *                        MEDICAL_HOLD 表示暂停期间被排除的计时
 */
public record SnapshotCheckpointRow(
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        Long elapsedMillis,
        String timingId,
        TimingExclusionReason exclusionReason
) {

    /** 兼容构造器：无排除原因。 */
    public SnapshotCheckpointRow(
            String raceId,
            String bib,
            String checkpointCode,
            int position,
            Long elapsedMillis,
            String timingId) {
        this(raceId, bib, checkpointCode, position, elapsedMillis, timingId, null);
    }
}
