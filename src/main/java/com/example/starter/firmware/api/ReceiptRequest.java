package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * 增量任务的 SUCCESS 回执必须携带任务代次、实际基底摘要与实际目标摘要，任一不符 409；
 * 普通任务沿用原接口，三个增量字段为 null。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        Integer taskGeneration,
        @Pattern(regexp = "^[0-9a-f]{64}$", message = "实际基底摘要须为64位小写十六进制")
        String actualBaseDigest,
        @Pattern(regexp = "^[0-9a-f]{64}$", message = "实际目标摘要须为64位小写十六进制")
        String actualTargetDigest) {

    /**
     * 兼容普通任务回执：不携带增量字段。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null, null, null);
    }
}