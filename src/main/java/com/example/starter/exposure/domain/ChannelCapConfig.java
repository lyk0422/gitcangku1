package com.example.starter.exposure.domain;

/**
 * 渠道日总量频控配置 PO。同一渠道按 UTC 自然日配置总确认额度；
 * 未配置的渠道不受该规则限制。
 *
 * @param channelKey   渠道编号，全局唯一
 * @param dailyCap     每 UTC 日总确认额度，单位次，取值 1～1000000
 * @param version      乐观版本号，从 1 开始，每次修改 +1；修改须携带 expectedVersion
 * @param createdAtUtc 创建时刻（epoch 毫秒，UTC）
 * @param updatedAtUtc 最近修改时刻（epoch 毫秒，UTC）
 */
public record ChannelCapConfig(
        String channelKey,
        int dailyCap,
        long version,
        long createdAtUtc,
        long updatedAtUtc
) {
}
