package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改活动配置请求。必须携带 expectedVersion 做乐观锁裁决；
 * 已开始小时（当天 h &lt;= 当前 UTC 小时）的预算不可下调，历史预占与确认记录不迁移。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param dailyTotalCap   新的每 UTC 日总确认额度，单位次，非负整数
 * @param hourlyBudget    新的 24 个小时预算，均为非负整数且合计必须等于 dailyTotalCap
 * @param expectedVersion 客户端持有的配置版本号；与当前版本不一致返回 409
 */
public record UpdateActivityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(0) @Max(100_000) Integer dailyTotalCap,
        @NotNull List<@NotNull @Min(0) @Max(100_000) Integer> hourlyBudget,
        @NotNull @Min(0) Long expectedVersion
) {
}
