package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 清除执行请求：仅本地角色 ERASER 可执行；按 requestId 幂等，重放返回首次的无载荷计数。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      待清除的已撤回授权代次，从 1 开始
 * @param role       调用方本地角色，仅 ERASER 允许执行，其他（含缺失）返回 403
 */
public record ErasureExecuteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch,
        @Size(max = 32) String role) {
}
