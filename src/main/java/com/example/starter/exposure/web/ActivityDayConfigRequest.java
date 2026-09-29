package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改（或新建）活动某 UTC 日投放配置请求。24 个小时预算均为非负整数且合计必须等于
 * {@code dailyTotalCap}；小时采用左闭右开区间 [h, h+1)，时间按 UTC。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param dailyTotalCap   当日总确认额度，单位次，非负整数
 * @param hourlyBudgets   长度恰为 24 的小时预算列表，下标即 UTC 小时（0～23），单位次，非负
 * @param expectedVersion 期望版本：null 表示新建（该日配置必须尚不存在）；非 null 时
 *                        仅当当前版本等于该值才修改成功，已开始小时预算不可下调
 */
public record ActivityDayConfigRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(0) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Size(min = 24, max = 24) List<@NotNull @Min(0) @Max(100_000) Integer> hourlyBudgets,
        @Min(1) Integer expectedVersion
) {
}
