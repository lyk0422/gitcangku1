package com.example.starter.exposure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 提交访客活动类别同意请求。
 *
 * @param requestId         写操作全局唯一幂等键
 * @param visitorId         合成访客编号
 * @param category          活动类别
 * @param decision          同意决定 ALLOW/DENY
 * @param consentVersion    同意版本号，正整数；高版本可在重叠区间覆盖低版本，DENY 不可被低版本覆盖
 * @param effectiveStartUtc 生效起点，epoch 毫秒，UTC（含）
 * @param effectiveEndUtc   生效终点，epoch 毫秒，UTC（不含）；null 表示长期有效
 */
public record SubmitConsentRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String visitorId,
        @NotBlank @Size(max = 64) String category,
        @NotBlank @Pattern(regexp = "ALLOW|DENY") String decision,
        @NotNull @Min(1) Integer consentVersion,
        @NotNull Long effectiveStartUtc,
        Long effectiveEndUtc
) {
}
