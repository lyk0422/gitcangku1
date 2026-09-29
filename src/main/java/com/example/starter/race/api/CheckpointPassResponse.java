package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个选手在单个检查点上的分段明细。
 *
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序，从1连续递增
 * @param elapsedMillis  通过累计耗时（毫秒）；尚未通过（缺失检查点）为 null
 * @param timingId       分段记录ID；尚未通过为 null
 * @param chipCode       该分段读数归属的芯片代码（封榜后为固化值，后续更换不改写）；尚未通过为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointPassResponse(
        String checkpointCode,
        int position,
        Long elapsedMillis,
        String timingId,
        String chipCode
) {
}
