package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

/**
 * 开立选手保证金账户请求。
 *
 * @param accountId  账户ID，全局唯一
 * @param holderName 账户持有人名称（展示用）；可空
 * @param requestId  全局唯一请求ID（幂等键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateAccountRequest(
        @NotBlank String accountId,
        String holderName,
        @NotBlank String requestId
) {
}
