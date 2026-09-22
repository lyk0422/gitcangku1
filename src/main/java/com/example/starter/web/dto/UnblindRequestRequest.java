package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 揭盲申请请求：协调员为已分配参与者提出带原因的申请。
 *
 * @param participantId 被申请揭盲的参与者编号
 * @param reason        揭盲原因
 * @param requestId     全局唯一写操作请求编号
 */
public record UnblindRequestRequest(
        @NotBlank String participantId,
        @NotBlank String reason,
        @NotBlank String requestId
) {
}
