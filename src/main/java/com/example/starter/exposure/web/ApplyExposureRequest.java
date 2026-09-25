package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 申请曝光请求。
 *
 * @param requestId    写操作全局唯一幂等键
 * @param campaignId   公告编号
 * @param visitorId    合成访客编号
 * @param placementId  展示位编号；抑制名单跨所有展示位生效，展示位仅参与请求指纹。
 *                     可空：旧调用方缺省时服务端归一化为默认展示位 "default"
 * @param requestAtUtc 请求时刻，epoch 毫秒，UTC，可空；显式携带时纳入 requestKey 指纹，
 *                     缺省由服务端在事务内取当前时刻
 */
public record ApplyExposureRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String visitorId,
        @Size(max = 64) String placementId,
        Long requestAtUtc
) {
    /** 兼容不区分展示位的调用方：归入默认展示位。 */
    public ApplyExposureRequest(String requestId, String campaignId, String visitorId) {
        this(requestId, campaignId, visitorId, DEFAULT_PLACEMENT, null);
    }

    /** 缺省展示位编号。 */
    public static final String DEFAULT_PLACEMENT = "default";
}
