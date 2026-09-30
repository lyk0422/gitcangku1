package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 解除设备隔离请求：按设备与目标固件版本定位当前隔离轮次；reviewer 为审核操作人，
 * 必须不同于该轮隔离确认人。解除只开启新一轮投放（本轮计数清零），不删除历史连续失败与隔离记录。
 */
public record ReleaseQuarantineRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String targetVersion,
        @NotBlank @Size(max = 64) String reviewer,
        @NotBlank @Size(max = 256) String reason) {
}
