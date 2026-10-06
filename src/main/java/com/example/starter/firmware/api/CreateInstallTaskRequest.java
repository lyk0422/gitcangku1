package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建安装握手任务请求：在指定发布单下为设备创建 INSTALL 类型任务（初始 PENDING，不占槽）。
 * expectedVersion 为期望的发布单版本号，用于乐观并发校验。
 */
public record CreateInstallTaskRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull Integer expectedVersion) {
}
