package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 试启动回执：设备下载固件后在非当前分区试启动，上报目标分区与目标版本；
 * 服务端记录 UTC 试启动时刻（不信任设备给的截止），进入 TRIAL 不更新 currentVersion。
 */
public record DualTrialRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Pattern(regexp = "[AB]") String targetPartition,
        @NotBlank @Size(max = 64) String targetVersion) {
}
