package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 配置赛事申诉保证金请求；配置金额对之后新建的申诉生效，不影响已创建申诉的快照金额。
 *
 * @param depositAmount   保证金金额（人民币元，正整数，1~1000000）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ConfigureDepositRequest(
        @NotNull @Positive @Max(1_000_000) Integer depositAmount,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
