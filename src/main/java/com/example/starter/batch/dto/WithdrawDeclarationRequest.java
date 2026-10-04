package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤回声明版本请求体；操作人通过 X-Actor-Id 请求头提供。
 */
public record WithdrawDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}