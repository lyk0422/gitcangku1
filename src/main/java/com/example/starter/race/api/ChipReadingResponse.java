package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 分段读数归属诊断项。
 *
 * @param timingId       分段记录ID
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序
 * @param elapsedMillis  通过累计耗时（毫秒）
 * @param createdAt      读数提交时刻，Unix毫秒时间戳（UTC）
 * @param chipId         当前归属芯片ID；未归属为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipReadingResponse(
        String timingId,
        String checkpointCode,
        int position,
        long elapsedMillis,
        long createdAt,
        String chipId
) {
}
