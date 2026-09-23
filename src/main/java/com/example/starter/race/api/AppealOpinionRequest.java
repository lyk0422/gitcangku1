package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 赛事干事提交申诉裁决意见请求。
 *
 * <p>第一人提交建议（recommendation）：UPHOLD / REMOVE / REPLACE；
 * REPLACE 必须携带非负 replacementMs（允许0）。第二人提交 action：
 * CONFIRM 时必须回传与第一人完全相同的 recommendation 与 replacementMs，
 * REJECT 时无需回传建议内容。
 *
 * @param stewardId      干事ID；两人不得相同
 * @param recommendation 建议：UPHOLD / REMOVE / REPLACE；第一人必填，第二人 CONFIRM 时回传
 * @param replacementMs  REPLACE 的非负替代罚时（毫秒，允许0）；其余情况为空
 * @param action         第二人动作：CONFIRM / REJECT；第一人留空
 * @param requestId      全局唯一请求ID（幂等键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppealOpinionRequest(
        @NotBlank String stewardId,
        String recommendation,
        @PositiveOrZero Long replacementMs,
        String action,
        @NotBlank String requestId
) {
}
