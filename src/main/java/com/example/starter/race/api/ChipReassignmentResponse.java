package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 芯片更换触发的单条分段读数归属变更明细。
 *
 * @param timingId       分段记录ID
 * @param checkpointCode 检查点代码
 * @param previousChipId 重算前归属芯片ID；此前未归属为 null
 * @param chipId         重算后归属芯片ID
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipReassignmentResponse(
        String timingId,
        String checkpointCode,
        String previousChipId,
        String chipId
) {
}
