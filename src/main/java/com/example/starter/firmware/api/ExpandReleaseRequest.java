package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 扩量请求：expectedVersion 必须等于发布单当前版本，成功加一；ratio 只增不减。
 */
public record ExpandReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) int expectedVersion,
        @Min(0) @Max(100) int ratio) {
}
