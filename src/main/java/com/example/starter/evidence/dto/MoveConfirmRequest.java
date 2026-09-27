package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 迁移第二人确认请求。操作人必须与首人确认人不同。
 *
 * @param commandKey 幂等命令键
 * @param moveKey    迁移业务键
 */
public record MoveConfirmRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String moveKey) {
}
