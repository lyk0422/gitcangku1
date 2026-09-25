package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 证明撤销请求：撤销“接收方＋用途＋代次”的当前生效证明，仅影响后续查询，不改写已生成快照。
 *
 * @param requestId   幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param recipientId 数据接收方标识
 * @param purpose     用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch       授权代次
 */
public record AttestRevokeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recipientId,
        @NotNull Purpose purpose,
        @NotNull Integer epoch) {
}
