package com.example.starter.race.persistence;

import com.example.starter.race.domain.TimingHoldStatus;
import com.example.starter.race.domain.TimingView;

/**
 * checkpoint_timing 表行记录（选手通过某检查点的分段记录）。
 *
 * @param timingId         全局唯一分段记录ID
 * @param raceId           所属赛事ID
 * @param bib              选手参赛号
 * @param checkpointCode   检查点代码
 * @param position         检查点顺序（配置时固化）
 * @param elapsedMillis    通过该检查点的累计耗时（毫秒，1~86400000）
 * @param holdStatus       计时资格：COUNTED-参与排名，MEDICAL_HOLD-医疗暂停期间提交，永久排除；写入时裁定，不可改写
 * @param excludedByHoldId 导致排除的医疗暂停ID；COUNTED 为 null
 * @param createdAt        提交时间，Unix毫秒时间戳
 */
public record CheckpointTimingRow(
        String timingId,
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        TimingHoldStatus holdStatus,
        String excludedByHoldId,
        long createdAt
) implements TimingView {
}
