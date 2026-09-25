package com.example.starter.blind.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 创建试验中心请求体。
 *
 * @param targetEnrollmentLimit 目标入组上限（人），非负；为 0 的中心允许创建但无法通过激活校验
 */
public record CreateSiteRequest(
        @NotNull(message = "targetEnrollmentLimit 不能为空")
        @Min(value = 0, message = "目标入组上限不能为负数")
        Integer targetEnrollmentLimit
) {
}
