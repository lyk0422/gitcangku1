package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接收方证明提交/续签请求：为“接收方＋用途＋代次”提交一条新版本证明。
 *
 * @param attestKey       幂等键，指纹含接收方、用途代次、到期与声明摘要；同键同参重放首次响应，失败不占键
 * @param recipientId     数据接收方标识
 * @param purpose         用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch           证明适用的授权代次，精确匹配，不可跨代复用
 * @param expiresAt       UTC 到期时刻，必须晚于提交时刻
 * @param statementDigest 声明摘要（合成字符串）
 */
public record AttestRequest(
        @NotBlank @Size(max = 128) String attestKey,
        @NotBlank @Size(max = 128) String recipientId,
        @NotNull Purpose purpose,
        @NotNull Integer epoch,
        @NotNull Instant expiresAt,
        @NotBlank @Size(max = 256) String statementDigest) {
}
