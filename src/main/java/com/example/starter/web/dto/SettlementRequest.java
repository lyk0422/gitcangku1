package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 结算请求；requestId 与承诺各自独立。
 *
 * @param userId    用户 ID
 * @param requestId 结算幂等 requestId
 */
public record SettlementRequest(
        @NotBlank String userId,
        @NotBlank String requestId
) {
}
