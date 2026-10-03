package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单条分段读数在某个时刻的芯片归属快照项。
 *
 * @param timingId       分段记录ID
 * @param checkpointCode 检查点代码
 * @param chipId         归属芯片ID；未归属为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipAttributionEntry(
        String timingId,
        String checkpointCode,
        String chipId
) {
}
