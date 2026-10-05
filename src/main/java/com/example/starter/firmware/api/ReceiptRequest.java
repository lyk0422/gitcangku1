package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED。
 * 普通灰度任务：首次回执终结任务。
 * 双分区任务：PENDING 时 SUCCESS 表示试启动（须携带 targetPartition/targetVersion），
 * TRIAL 时 SUCCESS 表示健康确认；FAILED 在任一未终结状态均触发回滚。
 * operator 为可选操作者标识，计入幂等指纹。
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        @Pattern(regexp = "A|B") String targetPartition,
        @Size(max = 64) String targetVersion,
        @Size(max = 64) String operator) {

    /**
     * 兼容旧调用方：仅携带 requestId 与结果。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null, null, null);
    }
}