package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 子范围创建请求：在当前有效代次内创建逻辑子范围，不产生新代次。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param scopeKey   子范围标识，同一 epoch 内唯一；default 为保留值不可使用
 * @param label      子范围非空标签（合成字符串）
 */
public record ScopeCreateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String scopeKey,
        @NotBlank @Size(max = 256) String label) {
}
