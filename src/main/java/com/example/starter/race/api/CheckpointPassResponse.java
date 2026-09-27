package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个选手在单个检查点上的分段明细。
 *
 * @param checkpointCode  检查点代码
 * @param position        检查点顺序，从1连续递增
 * @param elapsedMillis   通过累计耗时（毫秒）；尚未通过（缺失检查点）为 null
 * @param timingId        分段记录ID；尚未通过为 null
 * @param exclusionReason 计时排除原因；null 表示正常计时或缺失检查点，MEDICAL_HOLD 为暂停期间被排除
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointPassResponse(
        String checkpointCode,
        int position,
        Long elapsedMillis,
        String timingId,
        String exclusionReason
) {

    /** 无排除原因的兼容构造器。 */
    public CheckpointPassResponse(
            String checkpointCode,
            int position,
            Long elapsedMillis,
            String timingId) {
        this(checkpointCode, position, elapsedMillis, timingId, null);
    }
}
