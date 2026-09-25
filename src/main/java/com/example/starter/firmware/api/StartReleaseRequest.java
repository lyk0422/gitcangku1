package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发布单启动请求：DRAFT 发布单通过兼容预检后转为 ACTIVE；expectedVersion 必须等于当前发布单版本。
 */
public record StartReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) int expectedVersion) {
}
