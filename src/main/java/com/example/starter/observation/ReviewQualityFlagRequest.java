package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 质量标记复核请求：复核角色须与标记提交角色不同。
 *
 * @param requestId  全局唯一请求标识（幂等去重键）
 * @param conclusion 复核结论：CONFIRMED 或 DISMISSED
 * @param reason     复核理由
 * @param reviewedBy 复核角色
 */
public record ReviewQualityFlagRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull ReviewConclusion conclusion,
        @NotBlank @Size(max = 1024) String reason,
        @NotBlank @Size(max = 64) String reviewedBy) {
}
