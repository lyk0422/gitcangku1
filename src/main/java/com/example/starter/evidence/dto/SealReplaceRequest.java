package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更换封条请求：仅 ACTIVE 容器可换，封条版本递增 1；巡检见证版本必须与当前版本一致。
 *
 * @param commandKey 幂等命令键
 * @param note       换封条说明，非空
 */
public record SealReplaceRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String note) {
}
