package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 修订选手某计时点区间请求；与提交共用相邻严格递增不变量，与赛事版本共享乐观并发控制。
 *
 * @param checkpointCode  计时点代码；完赛区间固定为 FINISH
 * @param lowerMs         新区间下界（毫秒，1~86400000，闭区间含端点）
 * @param upperMs         新区间上界（毫秒，1~86400000，闭区间含端点），须大于等于下界
 * @param expectedVersion 客户端所见赛事版本
 * @param operator        操作者标识，非空
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ReviseIntervalRequest(
        @NotBlank String checkpointCode,
        @NotNull @Positive @Max(86_400_000L) Long lowerMs,
        @NotNull @Positive @Max(86_400_000L) Long upperMs,
        @NotNull Integer expectedVersion,
        @NotBlank String operator,
        @NotBlank String requestId
) {
}
