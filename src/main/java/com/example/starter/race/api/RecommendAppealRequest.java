package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 第一名赛事干事提交裁决建议请求。
 * decision=REPLACE 时必须携带非负 replacementMs；其余建议不得携带。
 *
 * @param officialId    赛事干事ID
 * @param decision      建议：UPHOLD / REMOVE / REPLACE
 * @param replacementMs 替代罚时（毫秒，非负）；仅 REPLACE 时有值
 * @param requestId     全局唯一请求ID（幂等键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecommendAppealRequest(
        @NotBlank String officialId,
        @NotBlank String decision,
        @PositiveOrZero Long replacementMs,
        @NotBlank String requestId
) {
}
