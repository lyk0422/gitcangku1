package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 更正选手某检查点分段累计耗时请求；更正后重算完整扣时集合与名次，
 * 不改写其他分段、原始完赛耗时与处罚。
 *
 * @param bib             参赛号
 * @param checkpointCode  被更正的检查点代码（必须已有分段记录）
 * @param elapsedMillis   更正后的累计耗时（毫秒，1~86400000），仍须满足严格递增与小于完赛耗时
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 * @param operator        操作者标识，非空
 */
public record CorrectTimingRequest(
        @NotBlank String bib,
        @NotBlank String checkpointCode,
        @NotNull @Positive @Max(86_400_000L) Long elapsedMillis,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId,
        @NotBlank String operator
) {
}
