package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用途额度调整请求：为指定授权代次的具体用途声明或调整查询额度。
 *
 * <p>额度按“主体＋用途＋epoch”独立计数；额度调整只影响后续查询，不改写已扣减账本。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      授权代次，从 1 开始
 * @param quotaLimit 要求的查询总额度（单位：对象数，非负）；首次出现即为声明，之后为调整
 */
public record QuotaAdjustRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch,
        @NotNull @Min(0) Long quotaLimit) {
}
