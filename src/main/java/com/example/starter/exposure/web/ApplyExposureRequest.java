package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 申请曝光请求。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param campaignId      公告编号
 * @param visitorId       合成访客编号
 * @param expectedVersion 调用方期望版本，可空；仅参与幂等身份结构化比较
 */
public record ApplyExposureRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String visitorId,
        Long expectedVersion
) {
    /**
     * 便捷构造：不带期望版本。
     */
    public ApplyExposureRequest(String requestId, String campaignId, String visitorId) {
        this(requestId, campaignId, visitorId, null);
    }
}
