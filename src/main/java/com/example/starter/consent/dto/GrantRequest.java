package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 授权请求：按“主体＋用途”创建带 UTC 到期时刻的新代次，或复用当前有效代次。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param expiresAt  授权到期时刻（UTC），必须晚于当前时刻；仅在新建代次时生效
 */
public record GrantRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull Instant expiresAt) {
}
