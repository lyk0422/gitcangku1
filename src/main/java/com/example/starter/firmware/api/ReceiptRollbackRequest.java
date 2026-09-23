package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回退波次回执请求。receiptKey 全局唯一；SUCCESS 原子切换设备版本，FAILED 保留版本。
 */
public record ReceiptRollbackRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String receiptKey,
        @NotNull ReceiptResult result) {
}
