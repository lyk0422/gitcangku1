package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * 增量任务的 SUCCESS 回执必须携带任务代次 imageGeneration、实际基底摘要 actualBaseDigest、
 * 实际目标摘要 actualTargetDigest（64 位十六进制），任一与服务端冻结值不符返回 409。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        Integer imageGeneration,
        @Size(max = 64) String actualBaseDigest,
        @Size(max = 64) String actualTargetDigest) {

    /**
     * 兼容旧调用方：普通发布回执不携带摘要与代次。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null, null, null);
    }
}