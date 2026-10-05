package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤回合格证声明请求。撤回只影响后续绑定与放行，不改写历史绑定与放行快照。
 */
public record WithdrawDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
