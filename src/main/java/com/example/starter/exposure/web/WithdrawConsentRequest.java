package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤回同意请求；目标同意编号来自路径。撤回只影响撤回之后的预占。
 *
 * @param requestId 写操作全局唯一幂等键
 */
public record WithdrawConsentRequest(
        @NotBlank @Size(max = 64) String requestId
) {
}
