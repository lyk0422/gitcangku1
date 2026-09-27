package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 已让渡占用重新申请请求。对原占用固化的时空参数重新审查并生成新占用；
 * 原占用不自动恢复。
 *
 * @param requestId          写操作全局唯一请求标识，用于幂等重放
 * @param sourceOccupationId 被让渡的原占用标识
 * @param newOccupationId    重新申请生成的新占用标识
 */
public record ReapplyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String sourceOccupationId,
        @NotBlank @Size(max = 64) String newOccupationId) {
}
