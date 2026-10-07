package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 完成接收 / 放弃接收命令请求，仅携带幂等键与期望版本。
 *
 * @param commandKey      幂等命令键（全局 requestId）
 * @param expectedVersion 期望的记录版本，与当前版本不一致返回 409
 */
public record ChunkCommandRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull @Min(0) Integer expectedVersion) {
}
