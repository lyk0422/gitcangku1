package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量启动请求：对一批设备创建投放任务。先校验全体设备的最终信任状态，
 * 任一设备不信任则返回 422 且整批任务不创建。
 */
public record BatchStartRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Size(max = 1000) List<@NotBlank @Size(max = 64) String> deviceIds) {
}
