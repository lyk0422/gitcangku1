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
 * @param medicalHold    提交时选手是否处于生效医疗暂停：true 为被排除计时，不参与排名且不可改写
 * @param holdId         排除该计时的医疗暂停ID；未被排除为 null
 * @param createdAt      提交时间，Unix毫秒时间戳
 */
public record CheckpointTimingRow(
        String timingId,
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        boolean medicalHold,
        String holdId,
        long createdAt
) implements TimingView {
}
