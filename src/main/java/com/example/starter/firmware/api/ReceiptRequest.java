package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * proofIndex 为安装证据索引：任务冻结了目标安全索引时，SUCCESS 必须携带同一索引；FAILED 不推进底线。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        @Min(0) Integer proofIndex) {

    /**
     * 兼容旧调用方：不携带安装证据索引。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null);
    }
}
