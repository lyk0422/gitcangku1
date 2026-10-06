package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * securityIndex 为设备出示的证明索引（安装证据），任务冻结了目标索引时 SUCCESS 必须携带同一索引。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        @Min(0) Integer securityIndex) {

    /**
     * 兼容旧调用方：不携带证明索引。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null);
    }
}
