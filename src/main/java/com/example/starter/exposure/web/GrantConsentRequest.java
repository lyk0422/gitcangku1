package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 提交访客同意请求。区间 [effectiveStartUtc, effectiveEndUtc) 左闭右开，单位 epoch 毫秒 UTC。
 *
 * @param requestId        写操作全局唯一幂等键；同键重放返回最初提交的同意判定
 * @param visitorId        合成访客编号
 * @param category         活动类别
 * @param decision         ALLOW 或 DENY
 * @param consentVersion   同意版本，同 (访客, 类别) 必须严格递增；DENY 不可被低版本覆盖
 * @param effectiveStartUtc 生效起点（含），epoch 毫秒，UTC
 * @param effectiveEndUtc  生效终点（不含），epoch 毫秒，UTC，必须大于起点
 */
public record GrantConsentRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String visitorId,
        @NotBlank @Size(max = 64) String category,
        @NotBlank String decision,
        @NotNull @Positive Long consentVersion,
        @NotNull Long effectiveStartUtc,
        @NotNull Long effectiveEndUtc
) {
}
