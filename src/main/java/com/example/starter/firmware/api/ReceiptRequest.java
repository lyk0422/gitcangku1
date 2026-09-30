package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * operator 为回执提交人，缺省为设备自身（device），第二次失败时即隔离确认人；
 * failureReason 为失败原因（长度不超过256），可空，触发隔离时随两次任务一并固化。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        @Size(max = 64) String operator,
        @Size(max = 256) String failureReason) {

    /**
     * 兼容旧调用方：不携带操作人与失败原因，操作人记为设备自身。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null, null);
    }

    /**
     * 有效操作人：缺省记为设备自身上报；裁剪首尾空白，空串视为未提供。
     */
    public String effectiveOperator() {
        if (operator == null || operator.isBlank()) {
            return "device";
        }
        return operator.trim();
    }

    /**
     * 有效失败原因：空串视为未上报，保留原文不做脱敏之外的改写。
     */
    public String effectiveFailureReason() {
        if (failureReason == null || failureReason.isBlank()) {
            return null;
        }
        return failureReason.trim();
    }
}
