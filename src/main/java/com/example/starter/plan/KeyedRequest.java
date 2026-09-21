package com.example.starter.plan;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅携带幂等键的写操作请求（发布、取消）。
 *
 * @param requestKey 幂等键
 */
public record KeyedRequest(@NotBlank String requestKey) {
}
