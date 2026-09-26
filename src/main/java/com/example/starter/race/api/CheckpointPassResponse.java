package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个选手在单个检查点上的分段明细。
 *
 * @param checkpointCode  检查点代码
 * @param position        检查点顺序，从1连续递增
 * @param elapsedMillis   通过累计耗时（毫秒）；尚未通过（缺失检查点）为 null
 * @param timingId        分段记录ID；尚未通过为 null
 * @param exclusionReason 该计时被排除的原因（MEDICAL_HOLD-医疗暂停排除）；未排除或缺失为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointPassResponse(
        String checkpointCode,
        int position,
        Long elapsedMillis,
        String timingId,
        String exclusionReason
) {
}
