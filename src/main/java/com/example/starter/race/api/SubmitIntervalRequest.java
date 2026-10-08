package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 提交选手区间计时记录请求（每计时点一条累计毫秒闭区间 [L,U]）。
 *
 * @param timingId        全局唯一区间记录ID（第二层幂等键）
 * @param checkpointCode  计时点代码；完赛区间固定为 FINISH
 * @param lowerMs         区间下界（毫秒，1~86400000，闭区间含端点）
 * @param upperMs         区间上界（毫秒，1~86400000，闭区间含端点），须大于等于下界
 * @param expectedVersion 客户端所见赛事版本
 * @param operator        操作者标识，非空
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record SubmitIntervalRequest(
        @NotBlank String timingId,
        @NotBlank String checkpointCode,
        @NotNull @Positive @Max(86_400_000L) Long lowerMs,
        @NotNull @Positive @Max(86_400_000L) Long upperMs,
        @NotNull Integer expectedVersion,
        @NotBlank String operator,
        @NotBlank String requestId
) {
}
