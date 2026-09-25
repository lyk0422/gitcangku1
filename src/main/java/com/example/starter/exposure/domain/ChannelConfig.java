package com.example.starter.exposure.domain;

/**
 * 渠道总量频控配置 PO。同一渠道按 UTC 自然日配置总确认（预占）额度；
 * 未配置渠道的公告申请不受渠道规则限制。
 *
 * @param channelKey    渠道编号，全局唯一
 * @param dailyTotalCap 该渠道每 UTC 日总确认额度，单位次，取值 1～100000
 * @param version       乐观锁版本号，初始 0，每次修改 +1；修改须携带 expectedVersion
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param updatedAtUtc  最近修改时刻，epoch 毫秒，UTC
 */
public record ChannelConfig(
        String channelKey,
        int dailyTotalCap,
        int version,
        long createdAtUtc,
        long updatedAtUtc
) {
}
