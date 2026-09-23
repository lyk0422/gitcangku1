package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 按展示位申请曝光请求。旧申请接口（不含展示位）等价于 placementCode={@code DEFAULT}。
 *
 * @param requestId     写操作全局唯一幂等键
 * @param campaignId    公告编号
 * @param placementCode 展示位编号，必须为该公告已创建的展示位
 * @param visitorId     合成访客编号
 */
public record ApplyPlacementExposureRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String placementCode,
        @NotBlank @Size(max = 64) String visitorId
) {
}
