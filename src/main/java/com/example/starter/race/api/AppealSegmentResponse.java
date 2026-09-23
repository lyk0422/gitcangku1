package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 申诉受理时冻结的单个检查点判定（只读证据）。
 *
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序，从1递增
 * @param elapsedMillis  冻结的累计耗时（毫秒）；缺失检查点为 null
 * @param timingId       冻结的分段记录ID；缺失检查点为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppealSegmentResponse(
        String checkpointCode,
        int position,
        Long elapsedMillis,
        String timingId
) {
}
