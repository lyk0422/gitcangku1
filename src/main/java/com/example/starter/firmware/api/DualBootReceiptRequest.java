package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 双分区任务回执请求。
 * action：TRIAL_BOOT 试启动（需携带 targetPartition、targetVersion）；
 * HEALTHY 健康确认（60 秒内有效，超时 409）；FAILED 失败（回滚）。
 * 试启动时刻与截止时刻均以服务端时钟为准，不信任设备上报。
 */
public record DualBootReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull DualBootReceiptAction action,
        @Pattern(regexp = "[AB]", message = "targetPartition 只能是 A 或 B") String targetPartition,
        @Size(max = 64) String targetVersion) {

    /**
     * 双分区回执动作。
     */
    public enum DualBootReceiptAction {
        TRIAL_BOOT,
        HEALTHY,
        FAILED
    }
}