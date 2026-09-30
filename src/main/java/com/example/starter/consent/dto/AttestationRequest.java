package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接收方承诺请求：接收方针对某个授权 epoch 声明用途与 UTC 到期时刻。
 *
 * @param requestId    幂等请求标识，同一 requestId 重放首次承诺版本，换用途或到期时间返回 409
 * @param recipientKey 接收方标识（合成字符串）
 * @param subjectKey   授权主体标识（合成字符串）
 * @param purpose      承诺用途：RESEARCH/PERSONALIZATION/AUDIT/MARKETING，读取时必须完全一致
 * @param epoch        承诺对应的授权代次，必须为存在的授权 epoch
 * @param expiresAt    承诺到期时刻（UTC，ISO-8601），到期时刻恰好到达即失效
 */
public record AttestationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recipientKey,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull Integer epoch,
        @NotNull Instant expiresAt) {
}
