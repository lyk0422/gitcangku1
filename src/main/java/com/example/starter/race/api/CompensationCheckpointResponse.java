package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个检查点的净计时补偿明细（只读）。
 *
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序
 * @param rawElapsedMs   原始通过累计耗时（毫秒），永不改写；缺失检查点为 null
 * @param netElapsedMs   净分段累计耗时（毫秒）=原始-累计补偿；缺失检查点为 null
 * @param compensationMs 该检查点累计补偿毫秒数；未受影响或缺失为 0
 * @param timingId       分段记录ID；缺失检查点为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompensationCheckpointResponse(
        String checkpointCode,
        int position,
        Long rawElapsedMs,
        Long netElapsedMs,
        long compensationMs,
        String timingId
) {
}
