package com.example.starter.race.api;

import com.example.starter.race.domain.TimingHoldStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 分段通过记录响应。
 *
 * @param timingId         全局唯一分段记录ID
 * @param bib              选手参赛号
 * @param checkpointCode   检查点代码
 * @param position         检查点顺序，从1连续递增
 * @param elapsedMillis    通过该检查点的累计耗时（毫秒）
 * @param holdStatus       计时资格：COUNTED-参与排名，MEDICAL_HOLD-医疗暂停期间提交，不参与排名
 * @param excludedByHoldId 导致排除的医疗暂停ID；COUNTED 为 null
 * @param createdAt        提交时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointTimingResponse(
        String timingId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        TimingHoldStatus holdStatus,
        String excludedByHoldId,
        long createdAt
) {
}
