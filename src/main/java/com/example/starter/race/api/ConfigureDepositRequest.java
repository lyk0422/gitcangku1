package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 配置赛事申诉保证金请求。
 * 金额以分为单位（1元=100分）；配置仅对之后创建的申诉生效，不改变已有申诉的快照金额。
 *
 * @param depositFen      保证金金额，单位：分，非负；0表示关闭申诉入口
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ConfigureDepositRequest(
        @NotNull @PositiveOrZero Long depositFen,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
