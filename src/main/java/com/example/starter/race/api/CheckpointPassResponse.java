package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个选手在单个检查点上的分段明细。
 *
 * @param checkpointCode   检查点代码
 * @param position         检查点顺序，从1连续递增
 * @param elapsedMillis    原始通过累计耗时（毫秒）；尚未通过（缺失检查点）为 null
 * @param netElapsedMillis 净通过累计耗时（原始分段扣除中止补偿，毫秒）；尚未通过为 null
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
}
