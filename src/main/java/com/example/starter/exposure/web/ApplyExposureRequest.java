package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 申请曝光请求。requestKey 指纹包含活动版本、访客、展示位、请求时刻与全部频控影响字段。
 *
 * @param requestId    写操作全局唯一幂等键
 * @param campaignId   公告编号
 * @param visitorId    合成访客编号
 * @param placementId  展示位编号
 * @param requestAtUtc 客户端请求时刻，epoch 毫秒，UTC；作为幂等指纹组成部分
 */
public record ApplyExposureRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String visitorId,
        @NotBlank @Size(max = 64) String placementId,
        @NotNull Long requestAtUtc
) {
}
