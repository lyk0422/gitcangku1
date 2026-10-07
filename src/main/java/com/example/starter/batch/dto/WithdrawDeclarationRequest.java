package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤回声明请求体；撤回操作人通过 X-Actor-Id 请求头提供。
 * 撤回作用于 declarationKey 的全部 ACTIVE 版本，只影响后续绑定与放行，不改写历史。
 */
public record WithdrawDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
