package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 子范围创建请求：在指定的有效 epoch 内创建/复用同一 scope_key 的逻辑分区，不产生新 epoch。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      子范围所属授权代次，从 1 开始
 * @param scopeKey   子范围标识，同一 epoch 内唯一；已独立撤回的 scope_key 不可复用
 * @param label      子范围非空标签
 */
public record ScopeCreateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch,
        @NotBlank @Size(max = 128) String scopeKey,
        @NotBlank @Size(max = 256) String label) {
}
