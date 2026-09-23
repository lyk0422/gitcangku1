package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 委托请求：在当前有效授权代次内，由主体或已获委托的处理方把指定用途向下委托给处理方。
 *
 * @param requestId     幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param delegationKey 委托边全局唯一键，重复使用返回 409
 * @param subjectKey    授权主体标识，禁止跨 subject 委托
 * @param purpose       用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param fromKey       委托方标识：首跳必须为主体标识，后续必须为已获委托的处理方
 * @param toKey         被委托处理方标识，不得为主体本身或链上已有节点
 * @param expiresAt     边到期时刻（UTC），不得晚于上级边与主体授权到期时刻
 */
public record DelegateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String delegationKey,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String fromKey,
        @NotBlank @Size(max = 128) String toKey,
        @NotNull Instant expiresAt) {
}
