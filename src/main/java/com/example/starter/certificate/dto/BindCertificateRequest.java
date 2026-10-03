package com.example.starter.certificate.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 批次绑定合格证声明请求体；操作者由 X-Actor-Id 请求头给出。
 */
public record BindCertificateRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @Min(value = 1, message = "version 必须不小于 1") int version
) {
}