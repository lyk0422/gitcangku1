package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 豁免包区域项签发请求。
 *
 * @param regionKey     区域唯一标识（对应禁飞区 zoneId）
 * @param regionVersion 审批时区域版本（禁飞区创建生效的全局空域版本）
 * @param validFrom     UTC 有效区间起点，epoch 毫秒（含端点）
 * @param validTo       UTC 有效区间终点，epoch 毫秒（含端点）
 * @param quota         额度，1~100 次
 */
public record PermitItemRequest(
        @NotBlank @Size(max = 64) String regionKey,
        @NotNull @Min(0) Long regionVersion,
        @NotNull Long validFrom,
        @NotNull Long validTo,
        @NotNull @Min(1) @Max(100) Integer quota) {
}
