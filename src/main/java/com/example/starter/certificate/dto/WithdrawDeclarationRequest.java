package com.example.starter.certificate.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤回合格证声明请求。
 */
public record WithdrawDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空") String reason
) {
}
