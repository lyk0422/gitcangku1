package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建渠道总量配置请求。
 *
 * @param requestId     写操作全局唯一幂等键
 * @param channelKey    渠道编号，全局唯一
 * @param dailyTotalCap 该渠道每 UTC 日总确认额度，单位次，取值 1～100000
 */
public record CreateChannelRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String channelKey,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap
) {
}
