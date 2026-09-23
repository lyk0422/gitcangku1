package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 委托请求：同一代次内，委托方把某用途处理权委托给处理方。
 *
 * <p>第一层委托方为主体；处理方可继续向下委托形成有向链。委托到期时刻不得晚于
 * 委托方自身授权/委托的到期时刻。
 *
 * @param requestId     幂等请求标识
 * @param delegationKey 委托边业务唯一键，全局唯一；续建使用新的 delegationKey
 * @param subjectKey    授权主体标识
 * @param purpose       用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch         委托所属授权代次
 * @param delegatorKey  委托方标识（主体或上级处理方）
 * @param processorKey  受托处理方标识
 * @param expiresAt     委托到期时刻（UTC），不得晚于委托方到期时刻
 */
public record DelegationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String delegationKey,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch,
        @NotBlank @Size(max = 128) String delegatorKey,
        @NotBlank @Size(max = 128) String processorKey,
        @NotNull Instant expiresAt) {
}
