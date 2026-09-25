package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修订冻结令请求：携带 expectedVersion 做乐观并发校验，窗口与范围整体替换，版本成功后加一。
 */
public record ReviseFreezeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Integer expectedVersion,
        @NotBlank String startUtc,
        @NotBlank String endUtc,
        List<@Size(max = 64) String> models,
        List<Long> releaseIds) {
}
