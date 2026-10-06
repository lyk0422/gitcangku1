package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 申请轮换创意曝光请求。按访客已确认次数模创意数选出下一个创意并冻结。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param campaignId 公告编号（须已配置 2～5 个有序创意）
 * @param visitorId  合成访客编号
 */
public record ApplyRotationRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String visitorId
) {
}
