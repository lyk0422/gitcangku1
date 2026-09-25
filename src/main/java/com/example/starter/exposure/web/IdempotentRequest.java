package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 仅携带幂等键的写操作请求体（如删除抑制区间）。
 *
 * @param requestId 写操作全局唯一幂等键
 */
public record IdempotentRequest(
        @NotBlank @Size(max = 64) String requestId
) {
}
