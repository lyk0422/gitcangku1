package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备拉取请求。taskType 缺省为 NORMAL（旧客户端只传 requestId 仍按原流程）；
 * BOOT_CHALLENGE 领取时冻结设备启动计数基线并生成唯一挑战串，要求非空操作者。
 */
public record PullRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 32) String taskType,
        @Size(max = 64) String operator) {
}
