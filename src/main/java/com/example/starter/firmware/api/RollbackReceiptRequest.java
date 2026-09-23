package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回跳任务回执请求：receiptKey 必须与派发时下发的凭证一致，result 为 SUCCESS 或 FAILED。
 */
public record RollbackReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String receiptKey,
        @NotNull ReceiptResult result) {
}
