package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 按展示位申请曝光请求。同一事务内同时占用公告当日总额度、
 * 该访客在公告下跨全部展示位共享的每日上限、指定展示位每日额度。
 *
 * @param requestId     写操作全局唯一幂等键
 * @param campaignId    公告编号
 * @param placementCode 展示位编号，须为公告已创建的展示位（旧申请接口等价于 DEFAULT）
 * @param visitorId     合成访客编号
 */
public record ApplyPlacementExposureRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(min = 1, max = 64) String placementCode,
        @NotBlank @Size(max = 64) String visitorId
) {
}
