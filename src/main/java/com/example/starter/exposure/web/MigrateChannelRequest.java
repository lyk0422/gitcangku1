package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 迁移公告归属渠道请求。只影响迁移后的新申请；
 * 既有预占始终按创建时固化的渠道结算。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param channelKey 目标渠道编号；null/空表示迁出渠道（不再受渠道频控）
 */
public record MigrateChannelRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String channelKey
) {
    /** 归一化渠道键：空白视为迁出渠道。 */
    public String normalizedChannelKey() {
        return (channelKey == null || channelKey.isBlank()) ? null : channelKey;
    }
}
