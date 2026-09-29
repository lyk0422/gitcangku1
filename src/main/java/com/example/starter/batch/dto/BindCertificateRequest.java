package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 单批次绑定合格证声明请求体；操作者通过 X-Actor-Id 请求头提供。
 */
public record BindCertificateRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey
) {
}
