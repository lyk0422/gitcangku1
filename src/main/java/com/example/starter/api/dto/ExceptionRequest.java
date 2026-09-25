package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建漏洞豁免请求：必须精确到锁定图版本与漏洞编号，携带 UTC 到期时刻与理由。
 *
 * <p>豁免不得按制品版本或仅漏洞编号跨锁定图复用；作用域由
 * （锁定图 ID + 漏洞编号）唯一确定。
 *
 * @param lockFileId 精确锁定图版本 ID
 * @param vulnerabilityId 漏洞编号，必须仍命中该锁定图中的某个制品坐标
 * @param expiresAt  豁免 UTC 到期时刻，必须晚于确认时刻；到期后豁免失效
 * @param reason     豁免理由，非空
 */
public record ExceptionRequest(
        @Positive long lockFileId,
        @NotBlank String vulnerabilityId,
        @NotNull Instant expiresAt,
        @NotBlank @Size(max = 1000) String reason) {
}
