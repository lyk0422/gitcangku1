package com.example.starter.race.persistence;

import com.example.starter.race.domain.TimingView;

/**
 * checkpoint_timing 表行记录（选手通过某检查点的分段记录）。
 *
 * @param timingId       全局唯一分段记录ID
 * @param raceId         所属赛事ID
 * @param bib            选手参赛号
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序（配置时固化）
 * @param elapsedMillis  通过该检查点的累计耗时（毫秒，1~86400000）
 * @param excludedHoldId 排除该计时的医疗暂停ID；非 null 表示 MEDICAL_HOLD，保存但不参与排名；null 为正常计时
 * @param createdAt      提交时间，Unix毫秒时间戳（UTC）
 */
public record CheckpointTimingRow(
        String timingId,
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        String excludedHoldId,
        long createdAt
) implements TimingView {

    /** 不含医疗暂停排除信息的兼容构造器（正常计时）。 */
    public CheckpointTimingRow(
            String timingId, String raceId, String bib, String checkpointCode,
            int position, long elapsedMillis, long createdAt) {
        this(timingId, raceId, bib, checkpointCode, position, elapsedMillis, null, createdAt);
    }
}
