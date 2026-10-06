package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 停止确认请求：仅同代次的 CANCEL_REQUESTED 任务转 CANCELLED 并释放安装槽。
 * expectedVersion 为期望的发布单版本号；generation 为设备持有的取消代次。
 */
public record StopConfirmRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Min(1) Integer generation) {
}
