package com.example.starter.race.api;

import com.example.starter.race.domain.TimingHoldStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个选手在单个检查点上的分段明细。
 *
 * @param checkpointCode   检查点代码
 * @param position         检查点顺序，从1连续递增
 * @param elapsedMillis    通过累计耗时（毫秒）；尚未通过（缺失检查点）为 null
 * @param timingId         分段记录ID；尚未通过为 null
 * @param holdStatus       计时资格：COUNTED / MEDICAL_HOLD；尚未通过为 null
 * @param excludedByHoldId 导致排除的医疗暂停ID；COUNTED 或缺失为 null
 * @param exclusionReason  排除原因；COUNTED 或缺失为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointPassResponse(
        String checkpointCode,
        int position,
        Long elapsedMillis,
        String timingId,
        TimingHoldStatus holdStatus,
        String excludedByHoldId,
        String exclusionReason
) {
}
