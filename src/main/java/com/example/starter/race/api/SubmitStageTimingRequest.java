package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 提交选手阶段检查点通过记录请求（阶段从零计时，累计毫秒）。
 *
 * @param requestId       全局唯一请求ID（写操作幂等键）
 * @param operator        非空操作者标识，参与幂等身份摘要
 * @param timingId        全局唯一阶段分段记录ID（第二层幂等键）
 * @param checkpointCode  通过的检查点代码（该阶段内）
 * @param elapsedMillis   阶段内累计耗时（毫秒，1~86400000）
 * @param expectedVersion 客户端所见赛事版本
 */
public record SubmitStageTimingRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String timingId,
        @NotBlank String checkpointCode,
        @NotNull @Positive @Max(86_400_000L) Long elapsedMillis,
        @NotNull Integer expectedVersion
) {
}
