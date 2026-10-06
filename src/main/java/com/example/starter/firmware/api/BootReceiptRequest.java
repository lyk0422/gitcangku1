package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.BootReceiptPhase;
import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 双分区试启动回执请求。result 为 SUCCESS 时必须携带 phase 区分试启动与正式切换：
 * TRIAL_BOOT 进入试启动（需带目标分区与目标版本），HEALTH_CONFIRM 健康确认；
 * result 为 FAILED 时任务直接回滚。
 */
public record BootReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        BootReceiptPhase phase,
        @Size(max = 1) String targetPartition,
        @Size(max = 64) String targetVersion) {
}
