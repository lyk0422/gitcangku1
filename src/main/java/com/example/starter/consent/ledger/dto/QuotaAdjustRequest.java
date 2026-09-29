package com.example.starter.consent.ledger.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用途额度调整请求：为指定主体的具体用途声明或调整受限查询额度。
 *
 * <p>额度可上调或下调；额度仅对当前有效授权代次（epoch）生效，撤回后不可再核销。
 *
 * @param requestId  幂等请求标识，同参重放首次快照，异参返回 409，失败不占键
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途标识（用途枚举名，如 RESEARCH / PERSONALIZATION）
 * @param epoch      额度归属的授权代次，从 1 开始
 * @param quota      要求的剩余额度（单位：个，对象数量；非负整数）
 */
public record QuotaAdjustRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 32) String purpose,
        @NotNull @Min(1) Integer epoch,
        @NotNull @Min(0) Long quota) {
}
