package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 第二名赛事干事确认或驳回裁决建议请求。
 * action=CONFIRM 时必须给出与第一人完全相同的 decision/replacementMs；
 * action=REJECT 时驳回第一人建议，申诉回到 PENDING，decision/replacementMs 不得携带。
 *
 * @param officialId    赛事干事ID，必须与第一人不同
 * @param action        动作：CONFIRM / REJECT
 * @param decision      确认的建议：UPHOLD / REMOVE / REPLACE；仅 CONFIRM 时有值
 * @param replacementMs 确认的替代罚时（毫秒，非负）；仅 CONFIRM+REPLACE 时有值
 * @param requestId     全局唯一请求ID（幂等键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfirmAppealRequest(
        @NotBlank String officialId,
        @NotBlank String action,
        String decision,
        @PositiveOrZero Long replacementMs,
        @NotBlank String requestId
) {
}
