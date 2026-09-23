package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 失败任务显式重试请求：指定当前发布单 expectedVersion 做乐观校验。
 */
public record RetryTaskRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) int expectedVersion) {
}
