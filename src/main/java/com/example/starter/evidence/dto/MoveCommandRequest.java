package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 仅携带幂等命令键的位置迁移操作请求，用于首人/第二人确认与撤销。
 *
 * @param commandKey 幂等命令键
 */
public record MoveCommandRequest(
        @NotBlank @Size(max = 64) String commandKey) {
}
