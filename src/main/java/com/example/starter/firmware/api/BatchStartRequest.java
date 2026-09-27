package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量启动请求：先校验全体设备的证书链、信任集合与兼容性，任一不过则全部任务不创建。
 */
public record BatchStartRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty List<@NotBlank @Size(max = 64) String> deviceIds,
        @NotBlank @Size(max = 64) String operator) {
}
