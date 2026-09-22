package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 提交选手检查点通过记录请求。
 *
 * @param timingId        全局唯一分段记录ID（第二层幂等键）
 * @param checkpointCode  通过的检查点代码
 * @param elapsedMillis   通过累计耗时（毫秒，1~86400000），必须小于该选手原始完赛耗时
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record SubmitTimingRequest(
        @NotBlank String timingId,
        @NotBlank String checkpointCode,
        @NotNull @Positive @Max(86_400_000L) Long elapsedMillis,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
