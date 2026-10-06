package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 * BOOT_CHALLENGE 任务必须携带非空 operator、与任务一致的 challenge；
 * SUCCESS 还须携带启动计数证明 bootCount（c 必须严格大于领取时冻结的 b 及提交时设备实际计数），
 * 超出 long 范围按 400 处理。普通任务无需上述证明字段。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        @Size(max = 64) String operator,
        @Size(max = 64) String challenge,
        Long bootCount) {

    /**
     * 兼容旧调用方：普通任务回执只需 requestId 与 result。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null, null, null);
    }
}
