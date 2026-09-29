package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * operator 为可选操作者（缺省 system），参与幂等指纹。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String operator,
        @NotNull ReceiptResult result) {

    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, null, result);
    }

    public String effectiveOperator() {
        return operator == null || operator.isBlank() ? CreateReleaseRequest.DEFAULT_OPERATOR : operator.trim();
    }
}
