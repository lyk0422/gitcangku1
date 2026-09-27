package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建活动请求。24 个小时预算均为非负整数且合计必须等于日总确认额度（服务层校验，不满足返回 400）。
 *
 * @param requestId      写操作全局唯一幂等键
 * @param activityId     活动编号，全局唯一
 * @param dailyTotalCap  每 UTC 日总确认额度，单位次
 * @param hourlyBudgets  恰好 24 个小时预算，按下标对应 UTC 小时 0~23；0 表示该小时未配置预算
 */
public record CreateActivityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String activityId,
        @NotNull @Min(0) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Size(min = 24, max = 24) List<@NotNull @Min(0) @Max(100_000) Integer> hourlyBudgets
) {
}
