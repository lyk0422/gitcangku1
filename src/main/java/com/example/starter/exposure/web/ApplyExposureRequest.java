package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 申请曝光请求。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param campaignId 公告编号
 * @param visitorId  合成访客编号
 * @param rotation   是否轮换申请；true 时按访客已确认轮换数取模选定并冻结创意，
 *                   缺省（null/false）为普通申请
 */
public record ApplyExposureRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String visitorId,
        Boolean rotation
) {
    /** 兼容普通申请（无轮换标记）。 */
    public ApplyExposureRequest(String requestId, String campaignId, String visitorId) {
        this(requestId, campaignId, visitorId, null);
    }

    /** 是否为轮换申请。 */
    public boolean isRotation() {
        return Boolean.TRUE.equals(rotation);
    }
}
