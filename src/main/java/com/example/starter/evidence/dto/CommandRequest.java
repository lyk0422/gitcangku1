package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 仅携带幂等命令键的命令请求，用于交接接受与取消。
 *
 * @param commandKey 幂等命令键
 */
public record CommandRequest(
        @NotBlank @Size(max = 64) String commandKey) {
}
