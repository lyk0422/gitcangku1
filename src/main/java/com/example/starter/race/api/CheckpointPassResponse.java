package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个选手在单个检查点上的分段明细。
 *
 * @param checkpointCode   检查点代码
 * @param position         检查点顺序，从1连续递增
 * @param elapsedMillis    通过累计耗时原始值（毫秒）；尚未通过（缺失检查点）为 null
 * @param netElapsedMillis 净耗时（毫秒）=原始耗时扣除受影响中止时长；尚未通过为 null
 * @param timingId         分段记录ID；尚未通过为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointPassResponse(
        String checkpointCode,
        int position,
        Long elapsedMillis,
        Long netElapsedMillis,
        String timingId
) {

    /** 无中止事件场景的兼容构造器：净耗时等于原始耗时。 */
    public CheckpointPassResponse(
            String checkpointCode, int position, Long elapsedMillis, String timingId) {
        this(checkpointCode, position, elapsedMillis, elapsedMillis, timingId);
    }
}
