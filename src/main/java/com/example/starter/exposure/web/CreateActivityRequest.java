package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建活动请求。hourlyBudgets 为 24 个 UTC 小时预算（0~23 时，单位次，非负整数），
 * 合计必须等于 dailyConfirmCap；缺省（null）表示暂不配置小时预算，
 * 未配置时归入该活动的公告不得申请曝光。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param activityId      活动编号，全局唯一
 * @param dailyConfirmCap 每 UTC 日总确认额度，单位次，取值 1~100000
 * @param hourlyBudgets   24 个小时预算；null 表示未配置
 */
public record CreateActivityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String activityId,
        @NotNull @Min(1) @Max(100_000) Integer dailyConfirmCap,
        @Size(min = 24, max = 24) List<@NotNull @Min(0) @Max(100_000) Integer> hourlyBudgets
) {
}
