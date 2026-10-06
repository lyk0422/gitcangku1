package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 仅携带全局幂等请求键的箱命令请求，用于根箱交接接受与取消。
 *
 * @param requestId 全局幂等请求键
 */
public record ContainerCommandRequest(
        @NotBlank @Size(max = 64) String requestId) {
}
