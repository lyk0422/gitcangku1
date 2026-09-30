package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * failureReason 仅在 FAILED 时有意义，作为隔离依据固化；operatorId 为回执操作人标识，
 * 第二次失败时记录为隔离确认人，解除审核必须由另一操作人完成。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        @Size(max = 256) String failureReason,
        @Size(max = 64) String operatorId) {

    /**
     * 兼容旧调用方：不上报失败原因与操作人。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null, null);
    }
}
