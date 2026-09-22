package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 参与者登记请求。
 *
 * @param participantId 合成参与者编号
 * @param requestId     全局唯一写操作请求编号
 */
public record EnrollRequest(
        @NotBlank String participantId,
        @NotBlank String requestId
) {
}
