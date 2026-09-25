package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修订冻结令请求：携带 expectedVersion 乐观校验，窗口结束必须晚于开始，范围至少一项。
 */
public record ReviseFreezeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Integer expectedVersion,
        List<@NotBlank @Size(max = 64) String> models,
        List<@NotNull Long> releaseIds,
        @NotBlank @Size(max = 40) String startUtc,
        @NotBlank @Size(max = 40) String endUtc,
        @Valid EmergencyException exception) {
}
