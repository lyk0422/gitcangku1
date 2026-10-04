package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 撤回声明版本请求；操作人通过 X-Actor-Id 请求头提供。
 * 撤回只影响后续绑定与放行，不改写已产生的绑定与放行快照。
 */
public record WithdrawCertificateRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "version 不能为空") Integer version,
        @NotBlank(message = "reason 不能为空") String reason
) {
}