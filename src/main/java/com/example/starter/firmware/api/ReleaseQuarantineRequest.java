package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备隔离解除审核请求。toVersion 指定解除对哪个目标固件版本的隔离；
 * 解除操作人必须不同于隔离确认人（第二次失败回执的 operatorId）；
 * 解除只开启该版本的新一轮投放，不清除历史失败次数。
 */
public record ReleaseQuarantineRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String toVersion,
        @NotBlank @Size(max = 64) String operatorId,
        @NotBlank @Size(max = 256) String reason) {
}
