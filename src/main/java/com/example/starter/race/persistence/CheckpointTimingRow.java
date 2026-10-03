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
 * @param createdAt      提交时间，Unix毫秒时间戳（UTC），芯片归属按该时刻判定
 * @param chipId         该读数归属的计时芯片ID；该选手尚无任何芯片更换记录时为 null
 */
public record CheckpointTimingRow(
        String timingId,
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        long createdAt,
        String chipId
) implements TimingView {
}
