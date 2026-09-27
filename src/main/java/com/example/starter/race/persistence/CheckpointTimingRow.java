package com.example.starter.race.persistence;

import com.example.starter.race.domain.TimingView;

/**
 * checkpoint_timing 表行记录（选手通过某检查点的分段记录）。
 *
 * @param timingId        全局唯一分段记录ID
 * @param raceId          所属赛事ID
 * @param bib             选手参赛号
 * @param checkpointCode  检查点代码
 * @param position        检查点顺序（配置时固化）
 * @param elapsedMillis   通过该检查点的累计耗时（毫秒，1~86400000）
 * @param excluded        是否被排除出排名：true-医疗暂停生效期间提交，不参与排名
 * @param exclusionReason 排除原因（MEDICAL_HOLD）；未排除为 null
 * @param holdId          导致排除的生效医疗暂停ID；未排除为 null
 * @param createdAt       提交时间，Unix毫秒时间戳
 */
public record CheckpointTimingRow(
        String timingId,
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        boolean excluded,
        String exclusionReason,
        String holdId,
        long createdAt
) implements TimingView {

    /** 兼容旧调用的构造器：未排除、无排除原因与暂停ID。 */
    public CheckpointTimingRow(
            String timingId,
            String raceId,
            String bib,
            String checkpointCode,
            int position,
            long elapsedMillis,
            long createdAt) {
        this(timingId, raceId, bib, checkpointCode, position, elapsedMillis,
                false, null, null, createdAt);
    }
}
