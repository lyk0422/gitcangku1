package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 豁免包区域项请求。UTC 半开有效区间 [validFrom, validTo)，额度 1~100 次。
 *
 * @param regionKey     区域（禁飞区）标识
 * @param regionVersion 区域版本，须与审核命中区域的生效空域版本一致
 * @param validFrom     有效起始时间（含），epoch 毫秒（UTC）
 * @param validTo       有效结束时间（不含），epoch 毫秒（UTC），须晚于 validFrom
 * @param quota         签发额度，1~100 次
 */
public record PermitItemRequest(
        @NotBlank @Size(max = 64) String regionKey,
        @NotNull @Min(0) Long regionVersion,
        @NotNull Long validFrom,
        @NotNull Long validTo,
        @NotNull @Min(1) @Max(100) Integer quota) {
}
