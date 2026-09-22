package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 制品撤回请求。
 */
public record WithdrawRequest(
        @NotBlank String requestId
) {
}
