package com.example.starter.certificate.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤回声明版本请求体；操作者由 X-Actor-Id 请求头给出。
 */
public record WithdrawDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}