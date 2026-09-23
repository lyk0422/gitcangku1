package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 失败任务显式重试请求：expectedVersion 必须等于发布单当前版本；
 * 重试不增加发布单版本，仅用于乐观并发校验。
 */
public record RetryTaskRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) int expectedVersion) {
}
