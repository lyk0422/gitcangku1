package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单个批次绑定合格证声明请求；操作人通过 X-Actor-Id 请求头提供。
 */
public record BindCertificateRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "batchKey 不能为空") String batchKey,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @NotNull(message = "declarationVersion 不能为空") Integer declarationVersion
) {
}