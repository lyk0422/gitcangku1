package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 异常段逐段处置请求：在解除温控冻结前，每个 EXCURSION 段都必须提交一次处置说明。
 */
public record DispositionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "actionNote 不能为空") String actionNote
) {
}
