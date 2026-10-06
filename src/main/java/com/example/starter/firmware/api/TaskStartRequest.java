package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 安装任务显式开始请求：PENDING 转 INSTALLING 并占设备独占安装槽。
 * expectedVersion 为期望的发布单版本号，用于乐观并发校验。
 */
public record TaskStartRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull Integer expectedVersion) {
}
