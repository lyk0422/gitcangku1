package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 参与者退组请求；席位保留，不重排已有分配。
 *
 * @param participantId 合成参与者编号
 * @param requestId     全局唯一写操作请求编号
 */
public record WithdrawRequest(
        @NotBlank String participantId,
        @NotBlank String requestId
) {
}
