package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * reason 为可选失败原因，仅首次 FAILED 时冻结记录，重复回执不覆盖。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        @Size(max = 256) String reason) {

    /**
     * 兼容旧调用方：不带失败原因。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null);
    }
}
