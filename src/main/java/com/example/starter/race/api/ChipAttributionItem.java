package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单条分段读数在某次芯片更换重算前后的归属明细（诊断/历史用）。
 *
 * @param timingId       分段记录ID
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序
 * @param elapsedMillis  通过累计耗时（毫秒）
 * @param readAtMs       读数已接收时刻（UTC 毫秒：实际上报时刻优先，否则为服务端接收时刻）
 * @param chipCode       该读数在对应时间线下归属的芯片代码；无法唯一归属为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipAttributionItem(
        String timingId,
        String checkpointCode,
        int position,
        long elapsedMillis,
        long readAtMs,
        String chipCode
) {
}
