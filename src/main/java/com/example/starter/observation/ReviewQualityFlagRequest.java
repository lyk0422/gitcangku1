package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 复核质量标记请求：由与标记提交人不同的角色提交复核结论与理由。
 *
 * @param requestId  全局唯一请求标识（幂等去重键）
 * @param flagKey    待复核的质量标记标识
 * @param conclusion 复核结论：CONFIRMED / DISMISSED
 * @param reason     复核理由
 * @param role       复核人角色，须与标记提交人角色不同
 */
public record ReviewQualityFlagRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String flagKey,
        @NotNull ReviewConclusion conclusion,
        @NotBlank @Size(max = 1024) String reason,
        @NotBlank @Size(max = 64) String role) {
}
