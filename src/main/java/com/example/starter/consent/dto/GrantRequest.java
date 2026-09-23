package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 授权请求：按“主体＋用途”创建或复用当前有效授权代次。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途代码（当前目录代次内存在的 ACTIVE 用途）
 */
public record GrantRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 64) String purpose) {
}
