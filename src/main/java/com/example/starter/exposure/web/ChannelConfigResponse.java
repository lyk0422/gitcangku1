package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.ChannelCapConfig;

/**
 * 渠道日总量频控配置视图。
 *
 * @param channelKey   渠道编号
 * @param dailyCap     每 UTC 日总确认额度，单位次
 * @param version      当前乐观版本号
 * @param createdAtUtc 创建时刻，epoch 毫秒，UTC
 * @param updatedAtUtc 最近修改时刻，epoch 毫秒，UTC
 */
public record ChannelConfigResponse(
        String channelKey,
        int dailyCap,
        long version,
        long createdAtUtc,
        long updatedAtUtc
) {
    public static ChannelConfigResponse from(ChannelCapConfig config) {
        return new ChannelConfigResponse(
                config.channelKey(),
                config.dailyCap(),
                config.version(),
                config.createdAtUtc(),
                config.updatedAtUtc());
    }
}
