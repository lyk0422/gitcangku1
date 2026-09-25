package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 跳级开关修改请求：expectedVersion 必须等于发布单当前版本，冲突返回 409；
 * 成功版本加一，只影响后续拉取，不改写已下发任务。
 */
public record SkipLevelRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) int expectedVersion,
        @NotNull Boolean allowSkip) {
}
