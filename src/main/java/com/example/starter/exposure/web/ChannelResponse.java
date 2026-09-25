package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.ChannelConfig;

/**
 * 渠道配置视图。
 *
 * @param channelKey    渠道编号
 * @param dailyTotalCap 每 UTC 日总确认额度，单位次
 * @param version       当前乐观锁版本号，初始 0，每次修改 +1
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param updatedAtUtc  最近修改时刻，epoch 毫秒，UTC
 */
public record ChannelResponse(
        String channelKey,
        int dailyTotalCap,
        int version,
        long createdAtUtc,
        long updatedAtUtc
) {
    public static ChannelResponse from(ChannelConfig config) {
        return new ChannelResponse(
                config.channelKey(),
                config.dailyTotalCap(),
                config.version(),
                config.createdAtUtc(),
                config.updatedAtUtc());
    }
}
