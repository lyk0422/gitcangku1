package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改渠道日总量额度请求。必须携带 expectedVersion 乐观锁版本：
 * 与当前版本不一致返回 409；不得下调到低于当前已确认（占用）数。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param dailyTotalCap   新的每 UTC 日总确认额度，单位次，取值 1～100000
 * @param expectedVersion 客户端持有的渠道配置版本号，初始版本为 0
 */
public record UpdateChannelCapRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(0) Integer expectedVersion
) {
}
