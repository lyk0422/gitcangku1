package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个选手在单个检查点上的分段明细。
 *
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序，从1连续递增
 * @param elapsedMillis  原始通过累计耗时（毫秒），永不被中止恢复改写；尚未通过为 null
 * @param netElapsedMs   净分段累计耗时（毫秒）=原始-累计补偿；尚未通过为 null，无中止事件时等于原始值
 * @param compensationMs 该检查点累计补偿毫秒数，未受影响为 0
 * @param timingId       分段记录ID；尚未通过为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointPassResponse(
        String checkpointCode,
        int position,
        Long elapsedMillis,
        Long netElapsedMs,
        long compensationMs,
        String timingId
) {

    /** 无中止补偿场景的兼容构造器：净值等于原始值、补偿为0。 */
    public CheckpointPassResponse(
            String checkpointCode,
            int position,
            Long elapsedMillis,
            String timingId) {
        this(checkpointCode, position, elapsedMillis, elapsedMillis, 0L, timingId);
    }
}
