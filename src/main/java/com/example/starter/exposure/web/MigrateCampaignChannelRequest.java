package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 公告迁移归属渠道请求。仅影响迁移后的新申请；既有预占始终按创建时固化的渠道结算。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param channelKey 新归属渠道编号；空串或 null 表示迁出（不归属任何渠道）
 */
public record MigrateCampaignChannelRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String channelKey
) {
}
