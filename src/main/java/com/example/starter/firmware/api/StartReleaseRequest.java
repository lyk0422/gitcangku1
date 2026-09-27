package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量启动发布请求：对给定设备集合统一校验信任状态后一次性创建任务；
 * 任一设备不信任或不兼容则整体 422，不创建任何任务。
 */
public record StartReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty List<@NotBlank @Size(max = 64) String> deviceIds) {
}
