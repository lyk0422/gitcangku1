package com.example.starter.consent.ledger.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用途额度清除请求：清除后额度状态为 CLEARED、余额为 null；历史账本条目不被改写。
 *
 * @param requestId  幂等请求标识，同参重放首次快照，异参返回 409，失败不占键
 * @param subjectKey 主体标识
 * @param purpose    用途标识
 * @param epoch      额度归属的授权代次
 */
public record QuotaClearRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 32) String purpose,
        @NotNull @Min(1) Integer epoch) {
}
