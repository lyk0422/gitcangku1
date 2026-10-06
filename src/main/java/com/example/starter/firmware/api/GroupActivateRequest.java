package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 组激活请求：expectedGeneration 必须等于组当前代次。
 * 全部当前代次成员暂存成功才允许激活；未齐备返回 422 且不占请求键，齐备后可同键重试。
 */
public record GroupActivateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @Min(1) int expectedGeneration) {
}
