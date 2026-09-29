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
 * @param chipCode       产生该读数并据 UTC 左闭右开区间判定归属的芯片代码；选手尚无芯片时为 null
 * @param readAtMs       芯片实际被读取的 UTC 时刻（毫秒）；null 表示未上报，归属按 created_at 判定
 * @param createdAt      记录接收（提交）时间，Unix毫秒时间戳（UTC）
 */
public record CheckpointTimingRow(
        String timingId,
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        String chipCode,
        Long readAtMs,
        long createdAt
) implements TimingView {
}
