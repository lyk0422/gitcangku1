package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 分段通过记录响应。
 *
 * @param timingId       全局唯一分段记录ID
 * @param bib            选手参赛号
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序，从1连续递增
 * @param elapsedMillis  通过该检查点的累计耗时（毫秒）
 * @param chipCode       产生该读数并归属的芯片代码；选手尚无芯片为 null
 * @param readAtMs       芯片实际读取 UTC 时刻（毫秒）；未上报为 null（按接收时刻归属）
 * @param createdAt      记录接收时间，Unix毫秒时间戳（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointTimingResponse(
        String timingId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        String chipCode,
        Long readAtMs,
        long createdAt
) {
}
