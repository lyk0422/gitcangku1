package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 任务开始请求：PENDING 显式开始后为 INSTALLING，占该设备独占安装槽。
 * operator 为非空操作者；expectedVersion 为期望的发布单版本，不一致返回 409。
 */
public record TaskStartRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(1) Integer expectedVersion) {
}
