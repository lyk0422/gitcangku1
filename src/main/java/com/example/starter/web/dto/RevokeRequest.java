package com.example.starter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 撤回请求，按指定代次撤回。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 或 PERSONALIZATION
 * @param epoch      待撤回的授权代次
 */
public record RevokeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 32) String purpose,
        @NotNull @Min(1) Integer epoch) {
}
