package com.example.starter.site.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅携带幂等命令键的请求体，用于批准、关闭、拆除操作。
 */
public record CommandRequest(@NotBlank String commandKey) {
}
