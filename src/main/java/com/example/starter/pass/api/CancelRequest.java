package com.example.starter.pass.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 取消接收计划请求。
 *
 * @param reason 明确的取消原因，非空，最终用户可见
 */
public record CancelRequest(
        @NotBlank String reason
) {
}
