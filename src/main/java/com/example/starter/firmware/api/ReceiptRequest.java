package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * reason 为失败原因，仅 FAILED 时原样固化，可不填；
 * operator 为回执提交操作人，触发设备隔离的连续第二次 FAILED 必填，作为隔离确认人。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        @Size(max = 512) String reason,
        @Size(max = 64) String operator) {

    /**
     * 兼容旧调用方：不带失败原因与操作人。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null, null);
    }
}
