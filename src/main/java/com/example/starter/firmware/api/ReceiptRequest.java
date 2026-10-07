package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * BOOT_CHALLENGE 任务必须携带匹配的挑战串 challenge 与非空操作者 operator；
 * SUCCESS 还须携带十进制文本 bootCount（严格大于冻结基线与设备实际计数）。
 * challenge/operator 文本原样比较，bootCount 按数值比较。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        @Size(max = 64) String challenge,
        @Size(max = 32) String bootCount,
        @Size(max = 64) String operator) {

    /**
     * 兼容旧调用方：普通任务回执只需 requestId 与 result。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null, null, null);
    }
}
