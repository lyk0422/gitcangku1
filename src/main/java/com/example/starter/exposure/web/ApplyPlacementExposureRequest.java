package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 按展示位申请曝光请求。同一事务内同时取得三层额度，任一已满返回 429 且三层均不增加。
 *
 * @param requestId     写操作全局唯一幂等键
 * @param campaignId    公告编号
 * @param placementCode 展示位编号（须已创建）
 * @param visitorId     合成访客编号；访客每日上限跨该公告全部展示位共享
 */
public record ApplyPlacementExposureRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String placementCode,
        @NotBlank @Size(max = 64) String visitorId
) {
}
